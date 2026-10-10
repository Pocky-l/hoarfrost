package com.pockyl.hoarfrost.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.compat.Climate;
import com.pockyl.hoarfrost.sim.AirFlow;
import com.pockyl.hoarfrost.sim.CoverChunk;
import com.pockyl.hoarfrost.sim.Flakes;
import com.pockyl.hoarfrost.sim.SnowCover;
import com.pockyl.hoarfrost.sim.SnowSettings;
import com.pockyl.hoarfrost.sim.SnowSimulation;
import com.pockyl.hoarfrost.weather.Noise;
import com.pockyl.hoarfrost.weather.SnowConditions;
import com.pockyl.hoarfrost.weather.SnowKind;
import com.pockyl.hoarfrost.weather.SnowSchedule;
import com.pockyl.hoarfrost.weather.Wind;
import com.pockyl.hoarfrost.world.DriftBuilder;

import java.util.Arrays;

/**
 * In-game tests, run headless by {@code gradlew runGameTestServer}. The snow simulation only needs a {@code Level}, so it
 * runs here against real blocks, stepped synchronously; the test world is warm, so the tests let it snow everywhere.
 * Most tests use the 40x14x40 {@code arena} structure with a stone floor at relative y = 0, so snow lies at y = 1.
 */
@GameTestHolder(Hoarfrost.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ModGameTests {
    private static final int SIZE = 40;
    /** Game time advanced per cover update in the tests. */
    private static final int DT = 100;

    private ModGameTests() {
    }

    @GameTest(template = "empty")
    public static void modLoads(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void kindsAndScheduleAreShared(GameTestHelper helper) {
        for (SnowKind kind : SnowKind.values()) {
            helper.assertTrue(SnowKind.byId(kind.id()) == kind, "kind id round trip for " + kind);
        }
        helper.assertTrue(SnowKind.byId("heavy_large_flakes") == SnowKind.HEAVY_LARGE_FLAKES, "command ids are lower case");
        int[] weights = {1, 1, 1, 1, 1, 1, 1};
        for (long t = 0; t < 400_000L; t += 6000L) {
            helper.assertTrue(SnowSchedule.naturalKind(t, true, true, weights).blizzard(), "a thunderstorm is one of the blizzards");
            helper.assertTrue(SnowSchedule.naturalKind(t, false, true, new int[] {0, 0, 0, 5, 0, 0, 0}) == SnowKind.HEAVY_LARGE_FLAKES,
                    "only the weighted kind is rolled");
        }
        helper.assertTrue(SnowSchedule.naturalKind(7L, false, true, new int[7]) == SnowKind.SNOW, "no weights fall back to snow");
        helper.assertTrue(Arrays.equals(SnowSchedule.seasonWeights(weights, -1.0F), weights), "without seasons the weights stay");
        int[] deepWinter = SnowSchedule.seasonWeights(weights, 1.0F);
        int[] earlyWinter = SnowSchedule.seasonWeights(weights, 0.0F);
        helper.assertTrue(deepWinter[SnowKind.BLIZZARD.ordinal()] > earlyWinter[SnowKind.BLIZZARD.ordinal()],
                "deep winter brings more blizzards");
        helper.assertTrue(earlyWinter[SnowKind.LIGHT_SNOW.ordinal()] > deepWinter[SnowKind.LIGHT_SNOW.ordinal()],
                "the edges of winter bring more light snow");
        // The course of the wind is Petrichor's, so rain and snow blow the same way.
        for (double t = 0; t < 200_000.0; t += 12_345.0) {
            float petrichor = (float) (Noise.value(t / 24000.0, 0x5EED_0003) * Math.PI * 4.0);
            helper.assertTrue(Wind.course(t) == petrichor, "the wind's course matches the rain's");
        }
        // Gusts and lulls average out around the mean wind.
        double sum = 0.0;
        int n = 0;
        for (int x = 0; x < 2000; x += 7) {
            for (int z = 0; z < 2000; z += 11) {
                sum += Wind.gust(x, z, 0.0, 0.0, 0.8F);
                n++;
            }
        }
        double mean = sum / n;
        helper.assertTrue(mean > 0.9 && mean < 1.1, "gusts average to the mean wind, got " + mean);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void worksWithoutSereneSeasons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.assertFalse(Climate.seasons(), "Serene Seasons is not installed in the test run");
        helper.assertTrue(Climate.winter(level) < 0.0F, "no season without Serene Seasons");
        helper.assertTrue(Climate.seasonName(level) == null, "no season name without Serene Seasons");
        Biome biome = level.getBiome(pos).value();
        Biome.Precipitation vanilla = biome.hasPrecipitation() ? biome.getPrecipitationAt(pos) : Biome.Precipitation.NONE;
        helper.assertTrue(Climate.precipitationAt(level, pos) == vanilla, "the biome's own climate decides");
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void calmSnowLiesEvenly(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowKind.SNOW);
        weather(sim, 60);
        double min = Double.MAX_VALUE;
        double max = 0.0;
        double sum = 0.0;
        int count = 0;
        double step = 0.0;
        for (int x = 8; x < SIZE - 8; x++) {
            for (int z = 8; z < SIZE - 8; z++) {
                double depth = depth(helper, sim, x, 1, z);
                min = Math.min(min, depth);
                max = Math.max(max, depth);
                sum += depth;
                count++;
                step = Math.max(step, Math.abs(depth - depth(helper, sim, x + 1, 1, z)));
            }
        }
        double mean = sum / count;
        helper.assertTrue(mean > 0.12 && mean < SnowCover.OPEN_DEPTH, "after a calm snowfall the snow is " + mean + " deep");
        helper.assertTrue(max - min < 0.01, "open flat ground is even: " + min + " .. " + max);
        helper.assertTrue(step < 0.002, "no steps between columns: " + step);
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void driftSettlesSmoothlyInTheLee(GameTestHelper helper) {
        floor(helper);
        for (int z = 4; z < SIZE - 4; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(10, y, z), Blocks.STONE);
            }
        }
        SnowSimulation sim = simulation(helper);
        storm(sim, SnowKind.BLIZZARD, 1.0F, 0.0F);
        weather(sim, 80);
        int z = SIZE / 2;
        double lee = 0.0;
        for (int x = 12; x <= 16; x++) {
            lee += depth(helper, sim, x, 1, z) / 5.0;
        }
        double open = 0.0;
        for (int x = 28; x <= 33; x++) {
            open += depth(helper, sim, x, 1, z) / 6.0;
        }
        helper.assertTrue(lee > 1.6 * open, "the lee collects snow: lee " + lee + " vs open " + open);
        // One rounded drift tapering off downwind, no ripples or steps.
        int peaks = 0;
        double steepest = 0.0;
        for (int x = 12; x <= 30; x++) {
            double here = depth(helper, sim, x, 1, z);
            double before = depth(helper, sim, x - 1, 1, z);
            double after = depth(helper, sim, x + 1, 1, z);
            if (here > before + 1.0E-4 && here > after + 1.0E-4) {
                peaks++;
            }
            steepest = Math.max(steepest, Math.abs(after - here));
        }
        helper.assertTrue(peaks <= 1, "a single drift without ripples, peaks: " + peaks);
        helper.assertTrue(steepest < 0.12, "the drift has no steps, steepest " + steepest);
        // Across the wind the drift is even.
        double spread = Math.abs(depth(helper, sim, 14, 1, z - 6) - depth(helper, sim, 14, 1, z + 6));
        helper.assertTrue(spread < 0.02, "even along the wall: " + spread);
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void windFillsACaveMouthAndFades(GameTestHelper helper) {
        floor(helper);
        // A hill of stone with a tunnel 4 wide and 3 high, its mouth facing the wind.
        for (int x = 8; x < 36; x++) {
            for (int z = 10; z < 30; z++) {
                for (int y = 1; y <= 7; y++) {
                    boolean tunnel = x < 34 && z >= 18 && z <= 21 && y <= 3;
                    if (!tunnel) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        SnowSimulation sim = simulation(helper);
        storm(sim, SnowKind.BLIZZARD, 1.0F, 0.0F);
        weather(sim, 120);
        double outside = depth(helper, sim, 4, 1, 19);
        double[] inside = new double[25];
        for (int k = 0; k < inside.length; k++) {
            inside[k] = (depth(helper, sim, 8 + k, 1, 19) + depth(helper, sim, 8 + k, 1, 20)) * 0.5;
        }
        helper.assertTrue(outside > 0.1, "snow lies outside the mouth: " + outside);
        helper.assertTrue(inside[0] > 0.05 && inside[2] > 0.03, "the wind blows snow into the mouth: " + inside[0] + ", " + inside[2]);
        helper.assertTrue(inside[10] < inside[1] * 0.25, "it fades with depth: " + inside[1] + " at 1, " + inside[10] + " at 10");
        helper.assertTrue(inside[16] < 0.006, "no snake of snow deep in the tunnel: " + inside[16] + " at 16");
        for (int k = 3; k < inside.length - 1; k++) {
            helper.assertTrue(inside[k + 1] <= inside[k] + 0.002, "never more snow further in, at " + k + ": " + Arrays.toString(inside));
        }
        // Across the tunnel the snow is even: a floor of snow, not a strip.
        double wall = depth(helper, sim, 10, 1, 18);
        double middle = depth(helper, sim, 10, 1, 20);
        helper.assertTrue(Math.abs(wall - middle) < Math.max(0.02, middle * 0.6), "even across the tunnel: " + wall + " vs " + middle);
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void evenCalmSnowUnderACrown(GameTestHelper helper) {
        floor(helper);
        int cx = 20;
        int cz = 20;
        for (int y = 1; y <= 5; y++) {
            helper.setBlock(new BlockPos(cx, y, cz), Blocks.OAK_LOG);
        }
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int x = cx - 4; x <= cx + 4; x++) {
            for (int z = cz - 4; z <= cz + 4; z++) {
                for (int y = 5; y <= 7; y++) {
                    if ((x != cx || z != cz || y > 5) && (Math.abs(x - cx) + Math.abs(z - cz) <= 6 || y == 6)) {
                        helper.setBlock(new BlockPos(x, y, z), leaves);
                    }
                }
            }
        }
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowKind.HEAVY_SNOW);
        weather(sim, 60);
        double open = depth(helper, sim, 6, 1, 6);
        double min = Double.MAX_VALUE;
        double max = 0.0;
        double step = 0.0;
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                if (x == cx && z == cz) {
                    continue;
                }
                double depth = depth(helper, sim, x, 1, z);
                min = Math.min(min, depth);
                max = Math.max(max, depth);
                if (x + 1 <= cx + 2 && (x + 1 != cx || z != cz)) {
                    step = Math.max(step, Math.abs(depth - depth(helper, sim, x + 1, 1, z)));
                }
            }
        }
        helper.assertTrue(min > 0.005, "some snow comes through the crown: " + min);
        helper.assertTrue(max < open * 0.6, "less under the crown than in the open: " + max + " vs " + open);
        helper.assertTrue(max - min < Math.max(0.02, max * 0.4), "even under the crown: " + min + " .. " + max);
        helper.assertTrue(step < 0.015, "no waves under the crown: " + step);
        helper.assertTrue(depth(helper, sim, cx + 1, 8, cz) > 0.05, "snow lies on the crown");
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void noSnowInAClosedRoom(GameTestHelper helper) {
        floor(helper);
        for (int x = 14; x <= 22; x++) {
            for (int z = 14; z <= 22; z++) {
                for (int y = 1; y <= 5; y++) {
                    boolean shell = x == 14 || x == 22 || z == 14 || z == 22 || y == 5;
                    if (shell) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.SPRUCE_PLANKS);
                    }
                }
            }
        }
        SnowSimulation sim = simulation(helper);
        storm(sim, SnowKind.HEAVY_BLIZZARD, 0.0F, 1.0F);
        weather(sim, 80);
        double inside = 0.0;
        for (int x = 15; x <= 21; x++) {
            for (int z = 15; z <= 21; z++) {
                inside = Math.max(inside, depth(helper, sim, x, 1, z));
            }
        }
        helper.assertTrue(inside == 0.0, "no snow inside: " + inside);
        helper.assertTrue(depth(helper, sim, 18, 6, 18) > 0.02, "snow on the roof");
        helper.assertTrue(depth(helper, sim, 6, 1, 6) > 0.05, "snow outside");
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void snowLiesOnVanillaSnowLayersAndMeltsAtLamps(GameTestHelper helper) {
        floor(helper);
        for (int x = 10; x < 20; x++) {
            for (int z = 10; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + (x + z) % 2));
            }
        }
        helper.setBlock(new BlockPos(28, 1, 28), Blocks.CAMPFIRE);
        // The light of the campfire spreads over the next ticks.
        helper.runAfterDelay(20, () -> {
            SnowSimulation sim = simulation(helper);
            calm(sim, SnowKind.SNOW);
            weather(sim, 40);
            for (int x = 11; x < 19; x++) {
                for (int z = 11; z < 19; z++) {
                    Vec3 at = helper.absoluteVec(new Vec3(x + 0.5, 1.4, z + 0.5));
                    CoverChunk chunk = sim.cover().chunkAt((int) Math.floor(at.x), (int) Math.floor(at.z));
                    int column = CoverChunk.index((int) Math.floor(at.x), (int) Math.floor(at.z));
                    float layer = (1 + (x + z) % 2) / 8.0F;
                    helper.assertTrue(Math.abs(chunk.base(CoverChunk.TOP, column) - (at.y - 1.4 + 1.0 + layer)) < 1.0E-4,
                            "the snow lies on the vanilla snow layer at " + x + ", " + z);
                }
            }
            helper.assertTrue(depth(helper, sim, 28, 1, 29) == 0.0, "no snow next to the campfire");
            helper.assertTrue(depth(helper, sim, 8, 1, 8) > 0.05, "snow away from it");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void aDugShaftStartsBare(GameTestHelper helper) {
        floor(helper);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
                }
            }
        }
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowKind.SNOW);
        weather(sim, 40);
        double around = depth(helper, sim, 23, 5, 20);
        // Dig a shaft three blocks down, block by block, as a player does.
        for (int y = 4; y >= 2; y--) {
            helper.setBlock(new BlockPos(20, y, 20), Blocks.AIR);
            BlockPos dug = helper.absolutePos(new BlockPos(20, y, 20));
            sim.cover().blockChanged(dug.getX(), dug.getZ());
            for (CoverChunk chunk : sim.cover().chunks()) {
                sim.prepare(helper.getLevel(), chunk);
            }
        }
        helper.assertTrue(depth(helper, sim, 20, 2, 20) == 0.0, "no snow at the bottom of the shaft: " + depth(helper, sim, 20, 2, 20));
        helper.assertTrue(Math.abs(depth(helper, sim, 23, 5, 20) - around) < 1.0E-4, "the snow around stays as it was");
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void airFlowsOverAndAroundAWall(GameTestHelper helper) {
        floor(helper);
        for (int z = 4; z < SIZE - 4; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(16, y, z), Blocks.STONE);
            }
        }
        SnowSettings settings = settings();
        SnowSimulation sim = new SnowSimulation(settings);
        SnowConditions weather = sim.weather();
        weather.snapTo(SnowKind.BLIZZARD);
        weather.gustiness = 0.0F;
        weather.turbulence = 0.0F;
        weather.setWind(1.0F, 0.0F, 0.5F);
        Vec3 center = helper.absoluteVec(new Vec3(20.0, 2.0, 20.0));
        AirFlow air = sim.air();
        air.update(helper.getLevel(), weather, center.x, center.y, center.z, sim.cold());
        // Upwind of the open spot the arena is clear for more than the reach of a lee (other tests stand beyond it).
        float[] open = sample(helper, air, 13.0, 8.0, 20.0);
        float[] windward = sample(helper, air, 15.4, 2.0, 20.0);
        float[] lee = sample(helper, air, 18.0, 1.5, 20.0);
        float[] low = sample(helper, air, 13.0, 1.3, 20.0);
        helper.assertTrue(open[0] > 0.4F, "the wind blows in the open: " + open[0]);
        helper.assertTrue(low[0] < open[0] * 0.8F, "it slows down near the ground: " + low[0]);
        helper.assertTrue(windward[1] > 0.01F, "it rises up the windward side: " + windward[1]);
        helper.assertTrue(lee[0] < open[0] * 0.3F, "it is calm in the lee: " + lee[0]);
        helper.assertTrue(lee[0] < 0.05F, "an eddy rolls back towards the wall in the lee: " + lee[0]);
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void windChargeLiftsPowderThatSettles(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowKind.HEAVY_SNOW);
        weather(sim, 40);
        sim.weather().snow = 0.0F;
        Vec3 blast = helper.absoluteVec(new Vec3(20.0, 1.3, 20.0));
        double before = sim.cover().total();
        double centre = depth(helper, sim, 20, 1, 20);
        sim.blast(blast.x, blast.y, blast.z, 2.6F, 0.45F);
        helper.assertTrue(depth(helper, sim, 20, 1, 20) < centre * 0.3, "the blast clears the snow around it");
        helper.assertTrue(sim.cover().total() < before, "snow was blown off the ground");
        Flakes flakes = sim.flakes();
        int lifted = flakes.count(Flakes.POWDER);
        helper.assertTrue(lifted > 150, "a cloud of powder rises: " + lifted);
        for (int t = 0; t < 8; t++) {
            sim.tick(helper.getLevel(), blast.x, blast.y + 1.5, blast.z);
        }
        double highest = 0.0;
        for (int i = 0; i < flakes.highWater(); i++) {
            if (flakes.kind[i] == Flakes.POWDER) {
                highest = Math.max(highest, flakes.y[i] - blast.y);
            }
        }
        helper.assertTrue(highest > 1.5, "the powder is thrown up high: " + highest);
        for (int t = 0; t < 400; t++) {
            sim.tick(helper.getLevel(), blast.x, blast.y + 1.5, blast.z);
        }
        helper.assertTrue(flakes.count(Flakes.POWDER) == 0, "the powder settles again: " + flakes.count(Flakes.POWDER));
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void feetPressDentsAndPackPaths(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowKind.SNOW);
        weather(sim, 40);
        Vec3 foot = helper.absoluteVec(new Vec3(20.5, 1.0, 20.5));
        double depth = sim.cover().depthAt(foot.x, foot.y, foot.z);
        sim.step(foot.x, foot.y, foot.z, 0.2F, 0.0F, 0.17F, 1.0F);
        CoverChunk chunk = sim.cover().chunkAt((int) Math.floor(foot.x), (int) Math.floor(foot.z));
        helper.assertTrue(chunk.dentCount() == 1, "a dent is pressed");
        helper.assertTrue(chunk.dentDepth(0) <= depth, "a dent goes no deeper than the snow");
        helper.assertTrue(sim.cover().packedAt(foot.x, foot.y, foot.z) > 0.0F, "the snow is trodden");
        helper.assertTrue(sim.cover().depthAt(foot.x, foot.y, foot.z) == depth, "a dent takes no snow away");
        helper.succeed();
    }

    @GameTest(template = "arena", skyAccess = true)
    public static void serverDriftsPileUpInTheLee(GameTestHelper helper) {
        floor(helper);
        for (int z = 4; z < SIZE - 4; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(10, y, z), Blocks.STONE);
            }
        }
        ServerLevel level = helper.getLevel();
        BlockPos lee = helper.absolutePos(new BlockPos(13, 1, 20));
        BlockPos open = helper.absolutePos(new BlockPos(32, 1, 20));
        for (int n = 0; n < 12; n++) {
            DriftBuilder.grow(level, lee.getX(), lee.getZ(), 1.0F, 0.0F, 4, true);
            DriftBuilder.grow(level, open.getX(), open.getZ(), 1.0F, 0.0F, 4, true);
        }
        BlockState leeState = level.getBlockState(lee);
        helper.assertTrue(leeState.getBlock() instanceof SnowLayerBlock && leeState.getValue(SnowLayerBlock.LAYERS) >= 2,
                "a drift of snow layers in the lee");
        helper.assertTrue(level.getBlockState(open).isAir(), "nothing on open ground");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static SnowSettings settings() {
        SnowSettings settings = new SnowSettings();
        settings.everywhereCold = true;
        settings.seed = false;
        return settings;
    }

    /** A simulation over the arena and the land around it, surveyed and bare. */
    private static SnowSimulation simulation(GameTestHelper helper) {
        SnowSimulation sim = new SnowSimulation(settings());
        BlockPos a = helper.absolutePos(BlockPos.ZERO);
        BlockPos b = helper.absolutePos(new BlockPos(SIZE - 1, 0, SIZE - 1));
        int minX = (Math.min(a.getX(), b.getX()) >> 4) - 1;
        int maxX = (Math.max(a.getX(), b.getX()) >> 4) + 1;
        int minZ = (Math.min(a.getZ(), b.getZ()) >> 4) - 1;
        int maxZ = (Math.max(a.getZ(), b.getZ()) >> 4) + 1;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                CoverChunk chunk = new CoverChunk(cx, cz);
                sim.cover().put(chunk);
                sim.prepare(helper.getLevel(), chunk);
            }
        }
        return sim;
    }

    private static void calm(SnowSimulation sim, SnowKind kind) {
        sim.weather().snapTo(kind);
        sim.weather().snow = 1.0F;
        sim.weather().setWind(1.0F, 0.0F, 0.0F);
    }

    private static void storm(SnowSimulation sim, SnowKind kind, float dirX, float dirZ) {
        sim.weather().snapTo(kind);
        sim.weather().snow = 1.0F;
        sim.weather().setWind(dirX, dirZ, kind.wind);
    }

    /** Lets {@code steps} x {@link #DT} ticks of the weather act on the snow. */
    private static void weather(SnowSimulation sim, int steps) {
        SnowConditions weather = sim.weather();
        float angle = (float) Math.atan2(weather.windDirZ, weather.windDirX);
        for (int step = 0; step < steps; step++) {
            for (CoverChunk chunk : sim.cover().chunks()) {
                sim.cover().update(chunk, weather, angle, weather.windiness(), DT);
            }
        }
    }

    /** Depth of the snow on the surface at relative block (x, y - 1, z), in its middle. */
    private static double depth(GameTestHelper helper, SnowSimulation sim, int x, int y, int z) {
        Vec3 at = helper.absoluteVec(new Vec3(x + 0.5, y, z + 0.5));
        return sim.cover().depthAt(at.x, at.y + 0.01, at.z);
    }

    private static float[] sample(GameTestHelper helper, AirFlow air, double x, double y, double z) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        float[] out = new float[3];
        air.sample(at.x, at.y, at.z, out);
        return out;
    }
}
