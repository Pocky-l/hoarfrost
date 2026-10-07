package com.pockyl.hoarfrost.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.sim.CoverChunk;
import com.pockyl.hoarfrost.sim.FlakePool;
import com.pockyl.hoarfrost.sim.SnowCover;
import com.pockyl.hoarfrost.sim.SnowSettings;
import com.pockyl.hoarfrost.sim.SnowSimulation;
import com.pockyl.hoarfrost.weather.SnowSchedule;
import com.pockyl.hoarfrost.weather.SnowType;
import com.pockyl.hoarfrost.world.DriftBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * In-game tests, run headless by {@code gradlew runGameTestServer}. The snow simulation only needs a {@code Level}, so it
 * runs here against real blocks, stepped synchronously; the test world is warm, so the tests let it snow everywhere.
 * Most tests use the 24x10x24 {@code field} structure with a stone floor at relative y = 0, so snow lies at y = 1.
 */
@GameTestHolder(Hoarfrost.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ModGameTests {
    private static final int SIZE = 24;
    /** Game time advanced per cover update in the tests. */
    private static final int DT = 100;

    private ModGameTests() {
    }

    @GameTest(template = "empty")
    public static void modLoads(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scheduleFollowsWeightsAndThunder(GameTestHelper helper) {
        helper.assertTrue(SnowSchedule.naturalType(12345L, true, true, new int[] {1, 1, 1, 0}) == SnowType.BLIZZARD,
                "a thunderstorm in snowy land is a blizzard");
        helper.assertTrue(SnowSchedule.naturalType(12345L, true, false, new int[] {0, 1, 0, 0}) == SnowType.SNOWFALL,
                "without the option thunder does not force a blizzard");
        for (long t = 0; t < 200_000L; t += 6000L) {
            helper.assertTrue(SnowSchedule.naturalType(t, false, true, new int[] {0, 0, 5, 0}) == SnowType.LARGE_FLAKES,
                    "only the weighted type is rolled");
        }
        helper.assertTrue(SnowSchedule.naturalType(7L, false, true, new int[] {0, 0, 0, 0}) == SnowType.SNOWFALL,
                "no weights fall back to a snowfall");
        helper.assertTrue(SnowSchedule.windSpeed(5000.0, 0.4F, 0.9F) == SnowSchedule.windSpeed(5000.0, 0.4F, 0.9F),
                "the wind is the same for everyone at the same time");
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void snowSettlesOverTheGround(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        long time = weather(helper, sim, 30);
        double min = Double.MAX_VALUE;
        double max = 0.0;
        double sum = 0.0;
        int cells = 0;
        for (double x = 2.125; x < SIZE - 2; x += SnowCover.CELL) {
            for (double z = 2.125; z < SIZE - 2; z += SnowCover.CELL) {
                double depth = depth(helper, sim, x, z);
                min = Math.min(min, depth);
                max = Math.max(max, depth);
                sum += depth;
                cells++;
            }
        }
        double mean = sum / cells;
        helper.assertTrue(min > 0.02, "every cell got snow, least " + min);
        helper.assertTrue(max <= SnowCover.NATURAL_DEPTH + 1.0E-4, "no cell beyond the natural depth, most " + max);
        helper.assertTrue(mean > 0.08 && mean < 0.25, "after " + time + " ticks of snowfall the snow is " + mean + " deep");
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void driftsGrowInTheLeeOfAWall(GameTestHelper helper) {
        floor(helper);
        wall(helper, 8, 3);
        SnowSimulation sim = simulation(helper);
        sim.weather().snapTo(SnowType.BLIZZARD);
        sim.weather().snow = 1.0F;
        sim.weather().windX = 0.45F;
        sim.weather().windZ = 0.0F;
        weather(helper, sim, 60);
        double lee = meanDepth(helper, sim, 9, 12);
        double open = meanDepth(helper, sim, 17, 21);
        helper.assertTrue(lee > 2.0 * open, "lee " + lee + " vs open " + open);
        helper.assertTrue(sim.cover().takeTransported() > 0.0F, "the wind moved snow");
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void snowSlidesDownToItsAngleOfRepose(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        sim.weather().snow = 0.0F;
        Vec3 heap = helper.absoluteVec(new Vec3(12.125, 1.0, 12.125));
        sim.cover().addPowder(heap.x, heap.z, 0.7F);
        double before = sim.cover().total();
        weather(helper, sim, 40);
        double after = sim.cover().total();
        helper.assertTrue(Math.abs(after - before) < before * 0.01, "snow is conserved: " + before + " -> " + after);
        float top = sim.cover().topAt(heap.x, heap.z);
        float next = sim.cover().topAt(heap.x + SnowCover.CELL, heap.z);
        helper.assertTrue(top - next <= SnowCover.REPOSE + 0.03F, "heap is still steeper than repose: " + (top - next));
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void snowSpillsOffAnEdge(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.STONE);
        helper.setBlock(new BlockPos(10, 2, 10), Blocks.STONE);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        sim.weather().snow = 0.0F;
        Vec3 corner = helper.absoluteVec(new Vec3(10.125, 3.0, 10.125));
        sim.cover().addPowder(corner.x, corner.z, 0.6F);
        weather(helper, sim, 30);
        float left = sim.cover().depthAt(corner.x, corner.z);
        helper.assertTrue(left <= SnowCover.EDGE_DEPTH + 0.01F, "the edge holds " + left);
        float below = sim.cover().depthAt(corner.x - SnowCover.CELL, corner.z) + sim.cover().depthAt(corner.x, corner.z - SnowCover.CELL);
        helper.assertTrue(below > 0.05F, "the snow fell to the ground below, " + below);
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void footPacksTheSnow(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        powder(helper, sim, 10, 15, 0.2F);
        Vec3 foot = helper.absoluteVec(new Vec3(12.5, 1.0, 12.5));
        float before = sim.cover().depthAt(foot.x, foot.z);
        float topBefore = sim.cover().topAt(foot.x, foot.z);
        float sank = sim.step(foot.x, foot.y, foot.z, 1.0F, 0.0F, 0.6F, 1, 0.1F, false);
        helper.assertTrue(sank > 0.15F, "the foot sank " + sank + " into " + before + ", top " + topBefore + " at foot y " + foot.y);
        double footZ = foot.z + 0.6 * 0.22;
        float depth = sim.cover().depthAt(foot.x, footZ);
        float powder = sim.cover().powderAt(foot.x, footZ);
        helper.assertTrue(powder == 0.0F && depth > 0.04F && depth < 0.08F, "print holds " + depth + " (powder " + powder + ")");
        float aside = sim.cover().depthAt(foot.x + 1.5, foot.z + 1.5);
        helper.assertTrue(Math.abs(aside - 0.2F) < 0.001F, "snow beside the print is untouched: " + aside);
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void explosionBlowsTheSnowAway(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        powder(helper, sim, 1, 23, 0.2F);
        Vec3 center = helper.absoluteVec(new Vec3(12.0, 1.0, 12.0));
        sim.explode(center.x, center.y, center.z, 3.0F, false);
        helper.assertTrue(sim.cover().depthAt(center.x + 0.5, center.z + 0.5) < 0.02F, "snow left at the center");
        helper.assertTrue(Math.abs(sim.cover().depthAt(center.x + 10.5, center.z + 10.5) - 0.2F) < 0.001F, "far snow is untouched");
        helper.assertTrue(sim.grainCount() > 0 || sim.pool().count() > 0, "a cloud of powder");
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true, timeoutTicks = 200)
    public static void lampsMeltTheSnow(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(12, 1, 12), Blocks.GLOWSTONE);
        // Light spreads over the next ticks.
        helper.runAfterDelay(20, () -> {
            SnowSimulation sim = simulation(helper);
            calm(sim, SnowType.SNOWFALL);
            sim.weather().snow = 0.0F;
            powder(helper, sim, 1, 23, 0.2F);
            weather(helper, sim, 10);
            Vec3 near = helper.absoluteVec(new Vec3(13.5, 1.0, 12.5));
            Vec3 far = helper.absoluteVec(new Vec3(20.5, 1.0, 20.5));
            helper.assertTrue(sim.cover().depthAt(near.x, near.z) < 0.01F, "snow next to the lamp melted");
            helper.assertTrue(sim.cover().depthAt(far.x, far.z) > 0.19F, "snow away from the lamp stays");
            helper.succeed();
        });
    }

    @GameTest(template = "field", skyAccess = true)
    public static void blockPlacedOnSnowStartsClean(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        powder(helper, sim, 8, 16, 0.2F);
        helper.setBlock(new BlockPos(12, 1, 12), Blocks.STONE);
        BlockPos block = helper.absolutePos(new BlockPos(12, 1, 12));
        sim.begin(helper.getLevel());
        sim.cover().blockChanged(block.getX(), block.getZ());
        Vec3 on = helper.absoluteVec(new Vec3(12.5, 2.0, 12.5));
        helper.assertTrue(sim.cover().depthAt(on.x, on.z) == 0.0F, "the new block top is bare");
        helper.assertTrue(Math.abs(sim.cover().topAt(on.x, on.z) - on.y) < 0.01, "the cell lies on the new block");
        helper.assertTrue(Math.abs(sim.cover().depthAt(on.x + 1.0, on.z) - 0.2F) < 0.001F, "snow next to it stays");
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void snowLiesOnVanillaSnowLayers(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(12, 1, 12), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2));
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        Vec3 on = helper.absoluteVec(new Vec3(12.5, 1.25, 12.5));
        float top = sim.cover().topAt(on.x, on.z);
        helper.assertTrue(Math.abs(top - on.y) < 0.01, "snow lies on the layer's top, not under it: " + (top - on.y));
        int flake = sim.addFlake(on.x, on.y + 4.0, on.z, 1.5F);
        for (int t = 0; t < 400 && sim.pool().state[flake] == FlakePool.FALLING; t++) {
            sim.stepFlakes(helper.getLevel(), on.x, on.y + 2.0, on.z);
        }
        helper.assertTrue(Math.abs(sim.pool().y[flake] - on.y) < 0.05, "flake lands on the layer at " + (sim.pool().y[flake] - on.y));
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void flakeLandsOnTheGround(GameTestHelper helper) {
        floor(helper);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        Vec3 start = helper.absoluteVec(new Vec3(12.5, 7.0, 12.5));
        int flake = sim.addFlake(start.x, start.y, start.z, 1.5F);
        FlakePool pool = sim.pool();
        for (int t = 0; t < 400 && pool.state[flake] == FlakePool.FALLING; t++) {
            sim.stepFlakes(helper.getLevel(), start.x, start.y - 4.0, start.z);
        }
        helper.assertTrue(pool.state[flake] == FlakePool.SETTLED, "flake did not land, state " + pool.state[flake]);
        double ground = helper.absoluteVec(new Vec3(0.0, 1.0, 0.0)).y;
        helper.assertTrue(Math.abs(pool.y[flake] - ground) < 0.05, "flake lies at " + (pool.y[flake] - ground) + " above the ground");
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void flakesDoNotPassThroughWalls(GameTestHelper helper) {
        floor(helper);
        wall(helper, 12, 9);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        sim.weather().windX = 0.12F;
        double wallX = helper.absoluteVec(new Vec3(12.0, 0.0, 0.0)).x;
        double wallTop = helper.absoluteVec(new Vec3(0.0, 10.0, 0.0)).y;
        FlakePool pool = sim.pool();
        int[] flakes = new int[12];
        for (int k = 0; k < flakes.length; k++) {
            Vec3 start = helper.absoluteVec(new Vec3(6.5 + k * 0.4, 3.0 + k * 0.4, 4.5 + k));
            flakes[k] = sim.addFlake(start.x, start.y, start.z, 1.5F);
        }
        Vec3 camera = helper.absoluteVec(new Vec3(12.0, 4.0, 12.0));
        for (int t = 0; t < 300; t++) {
            sim.stepFlakes(helper.getLevel(), camera.x, camera.y, camera.z);
            for (int flake : flakes) {
                if (pool.state[flake] != FlakePool.FREE && pool.y[flake] < wallTop) {
                    helper.assertTrue(pool.x[flake] < wallX + 0.01, "a flake went through the wall at tick " + t);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void airIsCalmInTheLee(GameTestHelper helper) {
        floor(helper);
        wall(helper, 8, 3);
        SnowSimulation sim = simulation(helper);
        calm(sim, SnowType.SNOWFALL);
        sim.weather().windX = 0.3F;
        Vec3 camera = helper.absoluteVec(new Vec3(12.0, 4.0, 12.0));
        sim.stepFlakes(helper.getLevel(), camera.x, camera.y, camera.z);
        sim.air().setTurbulence(false);
        float[] out = new float[3];
        double lee = 0.0;
        double open = 0.0;
        for (int z = 3; z <= 20; z++) {
            Vec3 a = helper.absoluteVec(new Vec3(9.5, 1.6, z + 0.5));
            Vec3 b = helper.absoluteVec(new Vec3(20.5, 1.6, z + 0.5));
            sim.air().sample(a.x, a.y, a.z, out);
            lee += out[0];
            sim.air().sample(b.x, b.y, b.z, out);
            open += out[0];
        }
        helper.assertTrue(open > 0.0 && lee < 0.4 * open, "wind behind the wall " + lee + " vs open " + open);
        helper.succeed();
    }

    @GameTest(template = "field", skyAccess = true)
    public static void serverDriftsPileUpInTheLee(GameTestHelper helper) {
        floor(helper);
        wall(helper, 8, 3);
        // A roof over open ground: no drift under it.
        for (int x = 15; x <= 17; x++) {
            for (int z = 11; z <= 13; z++) {
                helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(14, 1, 12), Blocks.STONE);
        helper.setBlock(new BlockPos(14, 2, 12), Blocks.STONE);
        helper.setBlock(new BlockPos(14, 3, 12), Blocks.STONE);
        helper.setBlock(new BlockPos(14, 4, 12), Blocks.STONE);
        ServerLevel level = helper.getLevel();
        BlockPos lee = helper.absolutePos(new BlockPos(9, 1, 12));
        BlockPos open = helper.absolutePos(new BlockPos(21, 1, 6));
        BlockPos roofed = helper.absolutePos(new BlockPos(16, 1, 12));
        for (int n = 0; n < 20; n++) {
            DriftBuilder.grow(level, lee.getX(), lee.getZ(), 1.0F, 0.0F, 4, true);
            DriftBuilder.grow(level, open.getX(), open.getZ(), 1.0F, 0.0F, 4, true);
            DriftBuilder.grow(level, roofed.getX(), roofed.getZ(), 1.0F, 0.0F, 4, true);
        }
        BlockState drift = level.getBlockState(lee);
        helper.assertTrue(drift.is(Blocks.SNOW) && drift.getValue(SnowLayerBlock.LAYERS) >= 2,
                "lee of the wall has " + drift);
        helper.assertTrue(level.getBlockState(open).isAir(), "open ground stays bare");
        helper.assertTrue(level.getBlockState(roofed).isAir(), "no drift under a roof");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void coverSurvivesSaving(GameTestHelper helper) {
        CoverChunk chunk = new CoverChunk(3, -7);
        chunk.base[100] = 64.5F;
        chunk.powder[100] = 0.21F;
        chunk.packed[100] = 0.05F;
        chunk.kind[100] = CoverChunk.LEAVES;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            chunk.write(new DataOutputStream(bytes));
            CoverChunk read = CoverChunk.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            helper.assertTrue(read.x == 3 && read.z == -7, "position");
            helper.assertTrue(read.base[100] == 64.5F && read.kind[100] == CoverChunk.LEAVES, "surface");
            helper.assertTrue(Math.abs(read.powder[100] - 0.21F) < 0.001F && Math.abs(read.packed[100] - 0.05F) < 0.001F, "depths");
        } catch (IOException e) {
            helper.fail("could not write or read: " + e);
        }
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

    /** A wall across the whole field at relative x, from y = 1 up to {@code height}. */
    private static void wall(GameTestHelper helper, int x, int height) {
        for (int z = 0; z < SIZE; z++) {
            for (int y = 1; y <= height; y++) {
                helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            }
        }
    }

    /** A simulation over the field, built after the blocks are placed. */
    private static SnowSimulation simulation(GameTestHelper helper) {
        SnowSettings settings = new SnowSettings();
        settings.maxFlakes = 2000;
        SnowSimulation sim = new SnowSimulation(settings);
        sim.terrain().forceSnow(true);
        sim.begin(helper.getLevel());
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        BlockPos max = helper.absolutePos(new BlockPos(SIZE - 1, 0, SIZE - 1));
        for (int cx = (Math.min(min.getX(), max.getX()) >> 4) - 1; cx <= (Math.max(min.getX(), max.getX()) >> 4) + 1; cx++) {
            for (int cz = (Math.min(min.getZ(), max.getZ()) >> 4) - 1; cz <= (Math.max(min.getZ(), max.getZ()) >> 4) + 1; cz++) {
                CoverChunk chunk = new CoverChunk(cx, cz);
                sim.cover().put(chunk);
                sim.cover().computeSurfaces(chunk);
            }
        }
        return sim;
    }

    private static void calm(SnowSimulation sim, SnowType type) {
        sim.weather().snapTo(type);
        sim.weather().snow = 1.0F;
        sim.weather().gust = 1.0F;
        sim.weather().windX = 0.0F;
        sim.weather().windZ = 0.0F;
    }

    /** Lets the weather work on the cover for {@code updates} steps of {@link #DT} ticks; returns the game time reached. */
    private static long weather(GameTestHelper helper, SnowSimulation sim, int updates) {
        Vec3 camera = helper.absoluteVec(new Vec3(12.0, 4.0, 12.0));
        long time = helper.getLevel().getGameTime();
        sim.updateAllCover(time);
        for (int n = 0; n < updates; n++) {
            time += DT;
            sim.stepFlakes(helper.getLevel(), camera.x, camera.y, camera.z);
            sim.updateAllCover(time);
        }
        return time - helper.getLevel().getGameTime();
    }

    private static void powder(GameTestHelper helper, SnowSimulation sim, int from, int to, float depth) {
        for (double x = from + 0.125; x < to; x += SnowCover.CELL) {
            for (double z = from + 0.125; z < to; z += SnowCover.CELL) {
                Vec3 at = helper.absoluteVec(new Vec3(x, 1.0, z));
                sim.cover().addPowder(at.x, at.z, depth);
            }
        }
    }

    private static double depth(GameTestHelper helper, SnowSimulation sim, double x, double z) {
        Vec3 at = helper.absoluteVec(new Vec3(x, 1.0, z));
        return sim.cover().depthAt(at.x, at.z);
    }

    /** Mean depth over the columns from relative x {@code from} to {@code to}, across the middle of the field. */
    private static double meanDepth(GameTestHelper helper, SnowSimulation sim, int from, int to) {
        double sum = 0.0;
        int cells = 0;
        for (double x = from + 0.125; x < to + 1; x += SnowCover.CELL) {
            for (double z = 4.125; z < SIZE - 4; z += SnowCover.CELL) {
                sum += depth(helper, sim, x, z);
                cells++;
            }
        }
        return sum / cells;
    }
}
