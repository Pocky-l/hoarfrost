package com.pockyl.hoarfrost.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.hoarfrost.compat.Climate;
import com.pockyl.hoarfrost.weather.SnowConditions;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * The snow around one point (the camera, or a spot in a game test): the air ({@link AirFlow}), the flakes and powder in it
 * ({@link Flakes}) and the snow lying on the land ({@link SnowCover}). Needs only a {@code Level}, so it runs on the client
 * and in game tests alike; the owner sets the weather every tick and calls {@link #tick}.
 *
 * <p>Flakes fall at their own speed through the moving air: they follow it with a little delay (large flakes more
 * slowly), sway and tumble on the way down, ride up over obstacles, curl into lees and are blown in under overhangs and
 * into cave mouths, where they settle. Snow lifted off the ground - by gusts, wind charges and explosions, by feet - is
 * powder: fine grains that rise in a cloud, drift with the wind and sink back slowly.
 */
public final class SnowSimulation {
    /** Half size of the box of the stream of flakes rushing past the face. */
    private static final float STREAM_X = 7.0F;
    private static final float STREAM_Y = 5.0F;
    /** Simulated flakes fill this height range around the camera. */
    private static final float NEAR_BELOW = 10.0F;
    private static final float NEAR_ABOVE = 18.0F;
    private static final float FAR_BELOW = 24.0F;
    private static final float FAR_ABOVE = 36.0F;
    /** Ticks between two chunks having their climate checked again. */
    private static final int CLIMATE_INTERVAL = 5;

    private final SnowSettings settings;
    private final SnowConditions weather;
    private final AirFlow air = new AirFlow();
    private final SnowCover cover = new SnowCover();
    private final CoverSurvey survey = new CoverSurvey();
    private final Flakes flakes;
    private final FastRandom random = new FastRandom(System.nanoTime());
    private final float[] scratch = new float[3];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BiPredicate<Level, BlockPos> cold;
    private double camX;
    private double camY;
    private double camZ;
    private int ticks;
    private boolean filled;
    private int climateCursor;

    public SnowSimulation(SnowSettings settings) {
        this(settings, new SnowConditions());
    }

    /** A simulation reading the weather from {@code weather}, which its owner keeps up to date. */
    public SnowSimulation(SnowSettings settings, SnowConditions weather) {
        this.settings = settings;
        this.weather = weather;
        this.flakes = new Flakes(settings.nearFlakes + settings.farFlakes + settings.streamFlakes + settings.powderGrains);
        this.cold = (level, at) -> settings.everywhereCold || Climate.snowsAt(level, at);
    }

    public SnowConditions weather() {
        return weather;
    }

    public AirFlow air() {
        return air;
    }

    public SnowCover cover() {
        return cover;
    }

    public Flakes flakes() {
        return flakes;
    }

    public SnowSettings settings() {
        return settings;
    }

    /** Where snow falls and stays, as this simulation sees it. */
    public BiPredicate<Level, BlockPos> cold() {
        return cold;
    }

    /** Surveys a chunk's land and lays its first snow, if not done yet. */
    public void prepare(Level level, CoverChunk chunk) {
        if (!chunk.surveyed()) {
            survey.survey(level, chunk, cold);
        }
        if (!chunk.seeded()) {
            cover.seed(chunk, settings.seed);
        }
    }

    /** The next chunk in turn has its climate checked again. */
    private void refreshClimate(Level level) {
        int size = cover.chunks().size();
        if (size == 0) {
            return;
        }
        int target = climateCursor++ % size;
        int index = 0;
        for (CoverChunk chunk : cover.chunks()) {
            if (index++ == target) {
                if (chunk.surveyed()) {
                    cover.refreshClimate(level, chunk, cold);
                }
                return;
            }
        }
    }

    public void tick(Level level, double cameraX, double cameraY, double cameraZ) {
        ticks++;
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        air.update(level, weather, camX, camY, camZ, cold);
        if (settings.cover) {
            cover.tick(weather);
            if (ticks % CLIMATE_INTERVAL == 0) {
                refreshClimate(level);
            }
        }
        moveNear(level);
        moveFar();
        moveStream();
        movePowder(level);
        spawnNear(level);
        spawnFar();
        spawnStream();
        if (settings.powder && settings.cover) {
            spindrift();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Flakes around the camera
    // ------------------------------------------------------------------------------------------------------------

    private int nearTarget() {
        return Math.round(settings.nearFlakes * settings.density * weather.density * weather.snow);
    }

    private void spawnNear(Level level) {
        int target = Math.min(nearTarget(), settings.nearFlakes);
        int missing = target - flakes.count(Flakes.NEAR);
        if (missing <= 0) {
            return;
        }
        // At first the whole space fills at once; afterwards falling flakes are replaced as they land or blow away.
        int budget = filled ? Math.max(24, target / 30) : missing;
        filled = true;
        float topShare = Math.clamp(weather.fallSpeed / (weather.fallSpeed + weather.windSpeed * 0.7F), 0.2F, 0.9F);
        float radius = settings.radius;
        for (int attempt = 0; attempt < budget * 2 && budget > 0; attempt++) {
            double x;
            double y;
            double z;
            float angle = random.next() * Mth.TWO_PI;
            float r = radius * (float) Math.sqrt(random.next());
            x = camX + Mth.cos(angle) * r;
            z = camZ + Mth.sin(angle) * r;
            if (random.next() < topShare) {
                y = camY + NEAR_ABOVE - random.next() * 3.0F;
            } else {
                y = camY - NEAR_BELOW + random.next() * (NEAR_ABOVE + NEAR_BELOW);
            }
            if (!air.snowy(x, z) || y < air.top(x, z) + 0.3 || !squall(x, z)) {
                continue;
            }
            int i = flakes.add(Flakes.NEAR, x, y, z);
            if (i < 0) {
                return;
            }
            initFlake(i, 1.0F);
            light(level, i);
            budget--;
        }
    }

    /** In a stormy wind the flakes crowd in the gusts: squalls of snow roll past between thinner stretches. */
    private boolean squall(double x, double z) {
        if (weather.gustiness < 0.45F) {
            return true;
        }
        float g = weather.gustAt(x, z);
        float crowd = (weather.gustiness - 0.45F) / 0.55F;
        float keep = Math.clamp(g * g / 1.6F, 0.12F, 1.0F);
        return random.next() < Mth.lerp(crowd, 1.0F, keep);
    }

    /** Size, shape, fall and sway of a new flake of the current weather. */
    private void initFlake(int i, float sizeScale) {
        float r = random.next();
        flakes.size[i] = Mth.lerp(r * r, weather.sizeMin, weather.sizeMax) * sizeScale;
        float roll = random.next();
        byte shape;
        float fall = weather.fallSpeed * random.range(0.82F, 1.18F);
        float sway = weather.flutter * random.range(0.5F, 1.4F);
        float swing = random.range(0.07F, 0.2F);
        float spin = random.signed() * 0.05F;
        if (roll < weather.crystals) {
            shape = random.next() < 0.5F ? Flakes.CRYSTAL : Flakes.CRYSTAL_ALT;
            sway *= 1.3F;
            spin *= 2.0F;
        } else if (roll < weather.crystals + weather.clumps) {
            shape = random.next() < 0.5F ? Flakes.CLUMP : Flakes.CLUMP_ALT;
            // Large aggregates tumble and swing slowly.
            sway *= 1.6F;
            swing *= 0.55F;
            spin *= 1.5F;
        } else if (roll < weather.crystals + weather.clumps + weather.grains) {
            shape = Flakes.GRAIN;
            fall *= 1.25F;
            sway *= 0.5F;
            flakes.size[i] *= 0.7F;
        } else {
            shape = flakes.size[i] < 1.4F ? Flakes.SPECK : Flakes.ROUND;
        }
        flakes.shape[i] = shape;
        flakes.fall[i] = fall;
        flakes.sway[i] = sway;
        flakes.swing[i] = swing;
        flakes.phase[i] = random.next() * Mth.TWO_PI;
        flakes.spin[i] = flakes.prevSpin[i] = random.next() * Mth.TWO_PI;
        flakes.spinRate[i] = spin;
        air.sample(flakes.x[i], flakes.y[i], flakes.z[i], scratch);
        flakes.vx[i] = scratch[0];
        flakes.vy[i] = scratch[1] - fall;
        flakes.vz[i] = scratch[2];
    }

    private void moveNear(Level level) {
        float radiusSq = settings.radius * settings.radius;
        int high = flakes.highWater();
        for (int i = 0; i < high; i++) {
            if (flakes.kind[i] != Flakes.NEAR) {
                continue;
            }
            float response = switch (flakes.shape[i]) {
                case Flakes.CLUMP, Flakes.CLUMP_ALT -> 0.2F;
                case Flakes.GRAIN -> 0.45F;
                default -> 0.32F;
            };
            if (!fly(level, i, response, 1.0F)) {
                flakes.remove(i);
                continue;
            }
            double dx = flakes.x[i] - camX;
            double dz = flakes.z[i] - camZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq > radiusSq || flakes.y[i] < camY - NEAR_BELOW - 6.0 || flakes.y[i] > camY + NEAR_ABOVE + 6.0) {
                flakes.remove(i);
                continue;
            }
            // Fade in when born, out towards the edge of the simulated space.
            float edge = Math.min(1.0F, (settings.radius - (float) Math.sqrt(distanceSq)) / 3.0F);
            flakes.alpha[i] = Math.min(edge, flakes.alpha[i] + 0.07F);
            if (((i + ticks) & 7) == 0) {
                light(level, i);
            }
        }
    }

    /**
     * Moves a flake or grain one tick through the air: it follows the air with a delay ({@code response} per tick), falls
     * at its own speed, sways. Returns false when it hit the land.
     */
    private boolean fly(Level level, int i, float response, float swayScale) {
        flakes.prevX[i] = flakes.x[i];
        flakes.prevY[i] = flakes.y[i];
        flakes.prevZ[i] = flakes.z[i];
        flakes.prevSpin[i] = flakes.spin[i];
        double x = flakes.x[i];
        double y = flakes.y[i];
        double z = flakes.z[i];
        air.sample(x, y, z, scratch);
        flakes.vx[i] += (scratch[0] - flakes.vx[i]) * response;
        flakes.vy[i] += (scratch[1] - flakes.fall[i] - flakes.vy[i]) * response;
        flakes.vz[i] += (scratch[2] - flakes.vz[i]) * response;
        float phase = flakes.phase[i] + flakes.swing[i];
        flakes.phase[i] = phase;
        float sway = flakes.sway[i] * swayScale;
        // A swing from side to side, bobbing a little at each end, like a falling leaf.
        float sx = Mth.cos(phase) * sway;
        float sz = Mth.sin(phase * 0.73F + i) * sway;
        float sy = Mth.sin(phase * 2.0F) * sway * 0.25F;
        x += flakes.vx[i] + sx;
        y += flakes.vy[i] + sy;
        z += flakes.vz[i] + sz;
        flakes.x[i] = x;
        flakes.y[i] = y;
        flakes.z[i] = z;
        flakes.spin[i] += flakes.spinRate[i];
        int top = air.top(x, z);
        if (top == Integer.MIN_VALUE) {
            return false;
        }
        if (y < top) {
            // Below the top of the column: under a crown or roof, in a cave mouth, or against a wall.
            pos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LEAVES) || !state.getFluidState().isEmpty()) {
                return false;
            }
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                double fy = y - pos.getY();
                return fy > state.getCollisionShape(level, pos).max(Direction.Axis.Y);
            }
        }
        return true;
    }

    private void light(Level level, int i) {
        pos.set(Mth.floor(flakes.x[i]), Mth.floor(flakes.y[i]), Mth.floor(flakes.z[i]));
        flakes.light[i] = level.getBrightness(LightLayer.BLOCK, pos) << 4 | level.getBrightness(LightLayer.SKY, pos) << 20;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Middle distance
    // ------------------------------------------------------------------------------------------------------------

    private void spawnFar() {
        int target = Math.min(settings.farFlakes, Math.round(settings.farFlakes * settings.farDensity * weather.density * weather.snow));
        int missing = target - flakes.count(Flakes.FAR);
        int budget = Math.min(missing, Math.max(40, target / 20));
        float inner = settings.radius - 2.0F;
        float outer = settings.farRadius;
        for (int attempt = 0; attempt < budget * 2 && budget > 0; attempt++) {
            float angle = random.next() * Mth.TWO_PI;
            float r = (float) Math.sqrt(Mth.lerp(random.next(), inner * inner, outer * outer));
            double x = camX + Mth.cos(angle) * r;
            double z = camZ + Mth.sin(angle) * r;
            double y = camY - FAR_BELOW + random.next() * (FAR_BELOW + FAR_ABOVE);
            if (!air.snowy(x, z) || y < air.top(x, z) + 0.5 || !squall(x, z)) {
                continue;
            }
            int i = flakes.add(Flakes.FAR, x, y, z);
            if (i < 0) {
                return;
            }
            initFlake(i, 1.0F);
            flakes.light[i] = 0xF00000;
            budget--;
        }
    }

    private void moveFar() {
        float inner = settings.radius - 4.0F;
        float outer = settings.farRadius + 2.0F;
        int high = flakes.highWater();
        for (int i = 0; i < high; i++) {
            if (flakes.kind[i] != Flakes.FAR) {
                continue;
            }
            flakes.prevX[i] = flakes.x[i];
            flakes.prevY[i] = flakes.y[i];
            flakes.prevZ[i] = flakes.z[i];
            flakes.prevSpin[i] = flakes.spin[i];
            double x = flakes.x[i];
            double z = flakes.z[i];
            float speed = weather.windSpeed * weather.gustAt(x, z) * 0.85F;
            float phase = flakes.phase[i] + flakes.swing[i];
            flakes.phase[i] = phase;
            flakes.vx[i] = weather.windDirX * speed + Mth.cos(phase) * flakes.sway[i];
            flakes.vy[i] = -flakes.fall[i];
            flakes.vz[i] = weather.windDirZ * speed + Mth.sin(phase * 0.73F) * flakes.sway[i];
            x += flakes.vx[i];
            z += flakes.vz[i];
            double y = flakes.y[i] + flakes.vy[i];
            flakes.x[i] = x;
            flakes.y[i] = y;
            flakes.z[i] = z;
            flakes.spin[i] += flakes.spinRate[i];
            double dx = x - camX;
            double dz = z - camZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            int top = air.top(x, z);
            if (distance < inner || distance > outer || top == Integer.MIN_VALUE || y < top || y < camY - FAR_BELOW - 8.0) {
                flakes.remove(i);
                continue;
            }
            float edge = Math.min(1.0F, Math.min((float) (distance - inner) / 4.0F, (float) (outer - distance) / 6.0F));
            flakes.alpha[i] = Math.min(edge, flakes.alpha[i] + 0.05F);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The stream past the face
    // ------------------------------------------------------------------------------------------------------------

    private void spawnStream() {
        int target = air.snowy(camX, camZ)
                ? Math.min(settings.streamFlakes, Math.round(settings.streamFlakes * weather.stream * weather.snow * settings.density))
                : 0;
        int count = flakes.count(Flakes.STREAM);
        if (count > target) {
            // Thinning out: the surplus fades away.
            int surplus = count - target;
            for (int i = 0, high = flakes.highWater(); i < high && surplus > 0; i++) {
                if (flakes.kind[i] == Flakes.STREAM && flakes.life[i] < 0.0F) {
                    flakes.life[i] = 10.0F;
                    surplus--;
                }
            }
            return;
        }
        int budget = Math.min(target - count, Math.max(30, target / 15));
        for (int n = 0; n < budget; n++) {
            double x = camX + random.signed() * STREAM_X;
            double y = camY + random.signed() * STREAM_Y;
            double z = camZ + random.signed() * STREAM_X;
            int i = flakes.add(Flakes.STREAM, x, y, z);
            if (i < 0) {
                return;
            }
            initFlake(i, 0.9F);
            flakes.light[i] = 0xF00000;
        }
    }

    private void moveStream() {
        air.sample(camX, camY, camZ, scratch);
        float ax = scratch[0];
        float ay = scratch[1];
        float az = scratch[2];
        int high = flakes.highWater();
        for (int i = 0; i < high; i++) {
            if (flakes.kind[i] != Flakes.STREAM) {
                continue;
            }
            float phase = flakes.phase[i] + flakes.swing[i];
            flakes.phase[i] = phase;
            // Each flake rides the air at the camera with its own drift, so the stream is not a rigid sheet.
            float own = 0.75F + 0.5F * ((i * 0x9E3779B9 >>> 24) / 255.0F);
            flakes.vx[i] = ax * own + Mth.cos(phase) * flakes.sway[i] * 2.0F;
            flakes.vy[i] = ay * own - flakes.fall[i] * 1.2F + Mth.sin(phase * 1.7F) * flakes.sway[i];
            flakes.vz[i] = az * own + Mth.sin(phase * 0.73F) * flakes.sway[i] * 2.0F;
            flakes.prevSpin[i] = flakes.spin[i];
            flakes.spin[i] += flakes.spinRate[i];
            double x = flakes.x[i] + flakes.vx[i];
            double y = flakes.y[i] + flakes.vy[i];
            double z = flakes.z[i] + flakes.vz[i];
            flakes.prevX[i] = flakes.x[i];
            flakes.prevY[i] = flakes.y[i];
            flakes.prevZ[i] = flakes.z[i];
            // The box travels with the camera: what leaves it on one side comes back on the other.
            double shiftX = wrap(x - camX, STREAM_X);
            double shiftY = wrap(y - camY, STREAM_Y);
            double shiftZ = wrap(z - camZ, STREAM_X);
            if (shiftX != 0.0 || shiftY != 0.0 || shiftZ != 0.0) {
                x += shiftX;
                y += shiftY;
                z += shiftZ;
                flakes.prevX[i] = x - flakes.vx[i];
                flakes.prevY[i] = y - flakes.vy[i];
                flakes.prevZ[i] = z - flakes.vz[i];
                flakes.alpha[i] = 0.0F;
            }
            flakes.x[i] = x;
            flakes.y[i] = y;
            flakes.z[i] = z;
            if (flakes.life[i] >= 0.0F && (flakes.life[i] -= 1.0F) <= 0.0F) {
                flakes.remove(i);
                continue;
            }
            float rx = (float) Math.abs(x - camX) / STREAM_X;
            float ry = (float) Math.abs(y - camY) / STREAM_Y;
            float rz = (float) Math.abs(z - camZ) / STREAM_X;
            float edge = Math.clamp((1.0F - Math.max(rx, Math.max(ry, rz))) * 4.0F, 0.0F, 1.0F);
            // Not under a roof or in the ground: there the stream does not blow.
            boolean open = y >= air.top(x, z);
            float target = open ? edge : 0.0F;
            if (flakes.life[i] >= 0.0F) {
                target *= flakes.life[i] / 10.0F;
            }
            flakes.alpha[i] += Math.clamp(target - flakes.alpha[i], -0.25F, 0.12F);
        }
    }

    private static double wrap(double offset, float half) {
        if (offset > half) {
            return -2.0 * half;
        }
        if (offset < -half) {
            return 2.0 * half;
        }
        return 0.0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Powder
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Throws up grains of powder.
     *
     * @param spread random part of the velocity
     * @param life   ticks the grains last at most
     */
    public void lift(double x, double y, double z, float vx, float vy, float vz, float spread, int count, float life) {
        int room = settings.powderGrains - flakes.count(Flakes.POWDER);
        for (int n = 0; n < Math.min(count, room); n++) {
            int i = flakes.add(Flakes.POWDER, x + random.signed() * 0.3, y + random.next() * 0.15, z + random.signed() * 0.3);
            if (i < 0) {
                return;
            }
            flakes.size[i] = random.range(0.6F, 1.6F);
            flakes.shape[i] = random.next() < 0.25F ? Flakes.PUFF : Flakes.GRAIN;
            if (flakes.shape[i] == Flakes.PUFF) {
                flakes.size[i] *= 3.0F;
            }
            flakes.fall[i] = random.range(0.012F, 0.032F);
            flakes.sway[i] = random.range(0.002F, 0.008F);
            flakes.swing[i] = random.range(0.1F, 0.3F);
            flakes.phase[i] = random.next() * Mth.TWO_PI;
            flakes.spinRate[i] = random.signed() * 0.15F;
            flakes.vx[i] = vx + random.signed() * spread;
            flakes.vy[i] = vy + random.signed() * spread * 0.6F;
            flakes.vz[i] = vz + random.signed() * spread;
            flakes.life[i] = life * random.range(0.5F, 1.0F);
            flakes.light[i] = 0xF00000;
        }
    }

    private void movePowder(Level level) {
        float reachSq = (settings.radius + 8.0F) * (settings.radius + 8.0F);
        int high = flakes.highWater();
        for (int i = 0; i < high; i++) {
            if (flakes.kind[i] != Flakes.POWDER) {
                continue;
            }
            // Fine grains: thrown fast, the air soon takes them over; they billow a little in it.
            flakes.vx[i] += random.signed() * 0.003F;
            flakes.vy[i] += random.signed() * 0.002F;
            flakes.vz[i] += random.signed() * 0.003F;
            boolean airborne = fly(level, i, 0.14F, 1.0F);
            float life = flakes.life[i] -= 1.0F;
            double dx = flakes.x[i] - camX;
            double dz = flakes.z[i] - camZ;
            if (!airborne || life <= 0.0F || dx * dx + dz * dz > reachSq) {
                flakes.remove(i);
                continue;
            }
            flakes.alpha[i] = Math.min(Math.min(1.0F, life / 25.0F), flakes.alpha[i] + 0.3F);
            if (((i + ticks) & 7) == 0) {
                light(level, i);
            }
        }
    }

    /** In strong wind, gusts lift loose powder off exposed snow; it skims along the ground in streaks. */
    private void spindrift() {
        float windiness = weather.windiness();
        if (windiness < 0.25F || weather.snow <= 0.0F && windiness < 0.4F) {
            return;
        }
        int tries = Math.round(48.0F * windiness * windiness);
        for (int n = 0; n < tries; n++) {
            float angle = random.next() * Mth.TWO_PI;
            float r = 18.0F * (float) Math.sqrt(random.next());
            double x = camX + Mth.cos(angle) * r;
            double z = camZ + Mth.sin(angle) * r;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            CoverChunk chunk = cover.chunkAt(bx, bz);
            if (chunk == null || !chunk.surveyed()) {
                continue;
            }
            int column = CoverChunk.index(bx, bz);
            float depth = chunk.depth(CoverChunk.TOP, column);
            float base = chunk.base(CoverChunk.TOP, column);
            if (depth < 0.03F || chunk.packed(CoverChunk.TOP, column) > 0.6F || base < air.top(x, z) - 0.01F) {
                continue;
            }
            double y = base + depth;
            air.sample(x, y + 0.4, z, scratch);
            float speed = (float) Math.sqrt(scratch[0] * scratch[0] + scratch[2] * scratch[2]);
            float excess = speed - 0.17F;
            if (excess <= 0.0F || random.next() > excess * 6.0F) {
                continue;
            }
            lift(x, y + 0.02, z, scratch[0], 0.02F + excess * 0.12F, scratch[2], 0.02F, 2 + (int) (excess * 20.0F), 70.0F);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A blast of air: a wind charge or an explosion. The air bursts out and up, carrying the falling flakes with it; the
     * snow around is blown off the ground into a cloud of powder that rises, drifts and settles.
     */
    public void blast(double x, double y, double z, float radius, float strength) {
        air.blast(x, y, z, radius, strength);
        if (!settings.cover) {
            return;
        }
        List<SnowCover.Blown> blown = cover.blast(x, y, z, radius, 1.0F);
        if (!settings.powder) {
            return;
        }
        for (SnowCover.Blown column : blown) {
            double dx = column.x() - x;
            double dz = column.z() - z;
            double distance = Math.max(0.3, Math.sqrt(dx * dx + dz * dz));
            float out = strength * 0.5F * (float) (1.0 - Math.min(1.0, distance / (radius * 1.5)));
            int count = Math.min(220, 4 + Math.round(column.amount() * 1100.0F));
            lift(column.x(), column.y(), column.z(), (float) (dx / distance) * out, 0.12F + strength * 0.45F, (float) (dz / distance) * out,
                    0.06F + strength * 0.12F, count, 220.0F);
        }
    }

    /**
     * A foot stepped down.
     *
     * @param moveX  movement this tick
     * @param weight 1 for a player; heavier creatures press deeper
     */
    public void step(double x, double y, double z, float moveX, float moveZ, float radius, float weight) {
        if (!settings.cover || !settings.footprints) {
            return;
        }
        float depth = cover.step(x, y, z, radius, weight);
        float speed = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (settings.powder && depth > 0.03F && speed > 0.05F) {
            int count = Math.round(Math.min(14.0F, (speed * 30.0F) * Math.min(1.0F, depth * 6.0F)));
            lift(x, y + 0.05, z, moveX * 0.4F, 0.05F + speed * 0.25F, moveZ * 0.4F, 0.03F, count, 60.0F);
        }
    }

    /** A creature landed in the snow from a fall: a deeper print and a puff of powder all around. */
    public void land(double x, double y, double z, float width, float fallSpeed) {
        if (!settings.cover || !settings.footprints) {
            return;
        }
        float depth = cover.step(x, y, z, width * 0.55F, 1.5F);
        if (settings.powder && depth > 0.02F) {
            int count = Math.round(Math.min(60.0F, fallSpeed * 70.0F));
            for (int n = 0; n < 8; n++) {
                float angle = n * Mth.TWO_PI / 8.0F;
                lift(x + Mth.cos(angle) * width * 0.4F, y + 0.05, z + Mth.sin(angle) * width * 0.4F, Mth.cos(angle) * 0.1F,
                        0.06F + fallSpeed * 0.1F, Mth.sin(angle) * 0.1F, 0.03F, count / 8, 80.0F);
            }
        }
    }

    /** A creature moving through the air drags it along. */
    public void wake(double x, double y, double z, float vx, float vy, float vz, float width, float height) {
        air.wake(x, y, z, vx, vy, vz, width * 0.5F, height);
    }
}
