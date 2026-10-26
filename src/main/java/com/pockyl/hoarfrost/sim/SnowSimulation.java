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
import com.pockyl.hoarfrost.world.LeafSnow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The snow around one point (the camera, or a spot in a game test): the air ({@link AirFlow}), the flakes and powder in it
 * ({@link Flakes}) and the snow lying on the land ({@link SnowCover}). Needs only a {@code Level}, so it runs on the client
 * and in game tests alike; the owner sets the weather every tick and calls {@link #tick}.
 *
 * <p>Flakes fall at their own speed through the moving air: they follow it with a little delay (large flakes more
 * slowly), sway and tumble on the way down, ride up over obstacles, curl into lees and are blown in under overhangs and
 * into cave mouths, where they settle. Snow lifted off the ground - by gusts and explosions, by feet - is
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
    /** Most a flake's speed changes in a tick, in blocks per tick (blasts throw powder much faster, then the air slows it). */
    private static final float MAX_ACCELERATION = 0.025F;
    /** Snow dust of a storm comes in within this distance of the camera. */
    private static final float GALE_RADIUS = 18.0F;
    /** Clouds of snow drift between these distances from the camera. */
    private static final float CLOUD_NEAR = 3.0F;
    private static final float CLOUD_FAR = 48.0F;
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
    private final List<long[]> pendingLight = new ArrayList<>();
    private float streamX;
    private float streamY;
    private float streamZ;

    public SnowSimulation(SnowSettings settings) {
        this(settings, new SnowConditions());
    }

    /** A simulation reading the weather from {@code weather}, which its owner keeps up to date. */
    public SnowSimulation(SnowSettings settings, SnowConditions weather) {
        this.settings = settings;
        this.weather = weather;
        this.flakes = new Flakes(settings.nearFlakes + settings.farFlakes + settings.streamFlakes + settings.powderGrains + settings.clouds);
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

    /**
     * Checks the climate and the lamps of the chunks around a block again soon (a lamp was placed or removed: its light
     * spreads over the next ticks).
     */
    public void lightChanged(int x, int z) {
        pendingLight.add(new long[] {x, z, ticks + 6L});
    }

    private void refreshPendingLight(Level level) {
        for (int n = pendingLight.size() - 1; n >= 0; n--) {
            long[] entry = pendingLight.get(n);
            if (entry[2] > ticks) {
                continue;
            }
            pendingLight.remove(n);
            for (int cx = ((int) entry[0] - 15) >> 4; cx <= ((int) entry[0] + 15) >> 4; cx++) {
                for (int cz = ((int) entry[1] - 15) >> 4; cz <= ((int) entry[1] + 15) >> 4; cz++) {
                    CoverChunk chunk = cover.chunk(cx, cz);
                    if (chunk != null && chunk.surveyed()) {
                        cover.refreshClimate(level, chunk, cold);
                    }
                }
            }
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
            refreshPendingLight(level);
        }
        moveClouds();
        moveNear(level);
        moveFar();
        moveStream();
        movePowder(level);
        spawnNear(level);
        spawnFar();
        spawnStream();
        spawnClouds(level);
        gale();
        if (settings.powder && settings.cover) {
            spindrift();
        }
        if (settings.powder && settings.leafSnow) {
            shedCrowns(level);
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
        float topShare = Mth.clamp(weather.fallSpeed / (weather.fallSpeed + weather.windSpeed * 0.7F), 0.2F, 0.9F);
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
        float keep = Mth.clamp(g * g / 1.6F, 0.12F, 1.0F);
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
                case Flakes.CLUMP, Flakes.CLUMP_ALT -> 0.12F;
                case Flakes.GRAIN -> 0.3F;
                default -> 0.2F;
            };
            if (!fly(level, i, response, 1.0F, true)) {
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
    private boolean fly(Level level, int i, float response, float swayScale, boolean inertia) {
        flakes.prevX[i] = flakes.x[i];
        flakes.prevY[i] = flakes.y[i];
        flakes.prevZ[i] = flakes.z[i];
        flakes.prevSpin[i] = flakes.spin[i];
        double x = flakes.x[i];
        double y = flakes.y[i];
        double z = flakes.z[i];
        air.sample(x, y, z, scratch);
        // A flake has inertia: however the air changes around it, its own speed changes only so fast, so it never jerks.
        float ax = (scratch[0] - flakes.vx[i]) * response;
        float ay = (scratch[1] - flakes.fall[i] - flakes.vy[i]) * response;
        float az = (scratch[2] - flakes.vz[i]) * response;
        float change = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (inertia && change > MAX_ACCELERATION) {
            float k = MAX_ACCELERATION / change;
            ax *= k;
            ay *= k;
            az *= k;
        }
        flakes.vx[i] += ax;
        flakes.vy[i] += ay;
        flakes.vz[i] += az;
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
            if (air.inStructure(level, x, y, z)) {
                return false;
            }
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
        // The stream follows the air around the camera with some inertia, as a mass of snow in the air does.
        air.sample(camX, camY, camZ, scratch);
        streamX += (scratch[0] - streamX) * 0.06F;
        streamY += (scratch[1] - streamY) * 0.06F;
        streamZ += (scratch[2] - streamZ) * 0.06F;
        float ax = streamX;
        float ay = streamY;
        float az = streamZ;
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
            float edge = Mth.clamp((1.0F - Math.max(rx, Math.max(ry, rz))) * 4.0F, 0.0F, 1.0F);
            // Not under a roof or in the ground: there the stream does not blow.
            boolean open = y >= air.top(x, z);
            float target = open ? edge : 0.0F;
            if (flakes.life[i] >= 0.0F) {
                target *= flakes.life[i] / 10.0F;
            }
            flakes.alpha[i] += Mth.clamp(target - flakes.alpha[i], -0.25F, 0.12F);
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
    // Clouds of snow
    // ------------------------------------------------------------------------------------------------------------

    private void spawnClouds(Level level) {
        int target = Math.min(settings.clouds, Math.round(settings.clouds * weather.clouds * weather.snow * settings.density));
        int missing = target - flakes.count(Flakes.CLOUD);
        for (int attempt = 0; attempt < Math.min(missing, 12) * 3 && missing > 0; attempt++) {
            float angle = random.next() * Mth.TWO_PI;
            float r = (float) Math.sqrt(Mth.lerp(random.next(), CLOUD_NEAR * CLOUD_NEAR, CLOUD_FAR * CLOUD_FAR));
            double x = camX + Mth.cos(angle) * r;
            double z = camZ + Mth.sin(angle) * r;
            int top = air.top(x, z);
            if (top == Integer.MIN_VALUE || !air.snowy(x, z)) {
                continue;
            }
            // Mostly low, rolling over the ground; some higher up.
            float height = 0.6F + 9.0F * random.next() * random.next();
            int i = flakes.add(Flakes.CLOUD, x, top + height, z);
            if (i < 0) {
                return;
            }
            flakes.size[i] = random.range(4.0F, 11.0F) * (0.7F + 0.3F * Math.min(1.0F, height / 3.0F));
            flakes.shape[i] = Flakes.VEIL;
            flakes.life[i] = random.range(160.0F, 380.0F);
            flakes.span[i] = flakes.life[i];
            flakes.spin[i] = flakes.prevSpin[i] = random.signed() * 0.3F;
            flakes.spinRate[i] = random.signed() * 0.002F;
            // How thick this cloud is.
            flakes.sway[i] = random.range(0.6F, 1.0F);
            air.sample(x, top + height, z, scratch);
            flakes.vx[i] = scratch[0];
            flakes.vy[i] = 0.0F;
            flakes.vz[i] = scratch[2];
            light(level, i);
            missing--;
        }
    }

    private void moveClouds() {
        int high = flakes.highWater();
        for (int i = 0; i < high; i++) {
            if (flakes.kind[i] != Flakes.CLOUD) {
                continue;
            }
            flakes.prevX[i] = flakes.x[i];
            flakes.prevY[i] = flakes.y[i];
            flakes.prevZ[i] = flakes.z[i];
            flakes.prevSpin[i] = flakes.spin[i];
            // A cloud is a large mass of air: it drifts with the wind around it and changes course slowly.
            air.sample(flakes.x[i], flakes.y[i], flakes.z[i], scratch);
            flakes.vx[i] += (scratch[0] - flakes.vx[i]) * 0.04F;
            flakes.vy[i] += (scratch[1] * 0.3F - flakes.vy[i]) * 0.04F;
            flakes.vz[i] += (scratch[2] - flakes.vz[i]) * 0.04F;
            double x = flakes.x[i] + flakes.vx[i];
            double y = flakes.y[i] + flakes.vy[i];
            double z = flakes.z[i] + flakes.vz[i];
            flakes.x[i] = x;
            flakes.y[i] = y;
            flakes.z[i] = z;
            flakes.spin[i] += flakes.spinRate[i];
            float life = flakes.life[i] -= 1.0F;
            double dx = x - camX;
            double dz = z - camZ;
            int top = air.top(x, z);
            // Where the ground is under it: a cloud is drawn no deeper than that, so it never cuts into the snow.
            flakes.phase[i] = top;
            if (life <= 0.0F || dx * dx + dz * dz > (CLOUD_FAR + 8.0) * (CLOUD_FAR + 8.0) || top == Integer.MIN_VALUE || y < top - 0.5) {
                flakes.remove(i);
                continue;
            }
            // Fades in and out over its life; denser where a gust carries it, so squalls roll past as clouds.
            float age = 1.0F - life / flakes.span[i];
            float envelope = Mth.sin((float) Math.PI * age);
            float gust = Mth.clamp((weather.gustAt(x, z) - 0.6F) / 1.0F, 0.15F, 1.3F);
            flakes.alpha[i] = envelope * gust * flakes.sway[i] * Math.min(1.0F, weather.clouds * 1.5F);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Powder
    // ------------------------------------------------------------------------------------------------------------

    /**
     * In a storm the air is full of snow dust driven by the wind: grains and soft puffs come in from upwind all around the
     * camera, close to the ground and higher up, and stream past with the gusts.
     */
    private void gale() {
        float strength = weather.stream * weather.snow * settings.density;
        if (strength <= 0.01F || !air.snowy(camX, camZ)) {
            return;
        }
        int target = Math.round(settings.powderGrains * 0.6F * Math.min(1.0F, strength));
        int missing = target - flakes.count(Flakes.POWDER);
        int budget = Math.min(missing, Math.max(10, target / 40));
        for (int n = 0; n < budget / 6; n++) {
            // Mostly upwind of the camera, so the dust blows through the view.
            double x = camX - weather.windDirX * random.range(0.0F, GALE_RADIUS) + random.signed() * GALE_RADIUS;
            double z = camZ - weather.windDirZ * random.range(0.0F, GALE_RADIUS) + random.signed() * GALE_RADIUS;
            int top = air.top(x, z);
            if (top == Integer.MIN_VALUE || !air.snowy(x, z)) {
                continue;
            }
            float height = 0.2F + 7.0F * random.next() * random.next();
            double y = top + height;
            if (y < camY - 10.0 || y > camY + 12.0) {
                continue;
            }
            float gust = weather.gustAt(x, z);
            if (random.next() > Mth.clamp(gust * gust / 1.4F, 0.25F, 1.0F)) {
                continue;
            }
            air.sample(x, y, z, scratch);
            lift(x, y, z, scratch[0], scratch[1] + 0.01F, scratch[2], 0.04F, 6, random.range(50.0F, 130.0F), 0.3F);
        }
    }

    /**
     * Throws up grains of powder.
     *
     * @param spread random part of the velocity
     * @param life   ticks the grains last at most
     */
    public void lift(double x, double y, double z, float vx, float vy, float vz, float spread, int count, float life) {
        lift(x, y, z, vx, vy, vz, spread, count, life, 0.3F);
    }

    /** As {@link #lift}, with the share of soft puffs among the grains. */
    public void lift(double x, double y, double z, float vx, float vy, float vz, float spread, int count, float life, float puffShare) {
        int room = settings.powderGrains - flakes.count(Flakes.POWDER);
        for (int n = 0; n < Math.min(count, room); n++) {
            int i = flakes.add(Flakes.POWDER, x + random.signed() * 0.3, y + random.next() * 0.15, z + random.signed() * 0.3);
            if (i < 0) {
                return;
            }
            flakes.size[i] = random.range(1.0F, 2.4F);
            flakes.shape[i] = random.next() < puffShare ? Flakes.PUFF : Flakes.GRAIN;
            if (flakes.shape[i] == Flakes.PUFF) {
                flakes.size[i] *= random.range(3.0F, 6.0F);
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
            boolean airborne = fly(level, i, 0.14F, 1.0F, false);
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

    /**
     * In strong wind, gusts lift loose powder off exposed snow; it skims along the ground in streaks. A moderate wind only
     * sets a few grains creeping over the snow, low and slow.
     */
    private void spindrift() {
        float windiness = weather.windiness();
        if (windiness < 0.25F) {
            creep(windiness);
            return;
        }
        if (weather.snow <= 0.0F && windiness < 0.4F) {
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

    /** A breeze over fresh snow: here and there a few grains roll and hop along the surface. */
    private void creep(float windiness) {
        if (windiness < 0.08F) {
            return;
        }
        for (int n = 0; n < 8; n++) {
            float angle = random.next() * Mth.TWO_PI;
            float r = 14.0F * (float) Math.sqrt(random.next());
            double x = camX + Mth.cos(angle) * r;
            double z = camZ + Mth.sin(angle) * r;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            CoverChunk chunk = cover.chunkAt(bx, bz);
            if (chunk == null || !chunk.surveyed() || random.next() > windiness * 1.2F * weather.gustAt(x, z)) {
                continue;
            }
            int column = CoverChunk.index(bx, bz);
            float depth = chunk.depth(CoverChunk.TOP, column);
            float base = chunk.base(CoverChunk.TOP, column);
            if (depth < 0.02F || chunk.packed(CoverChunk.TOP, column) > 0.5F || base < air.top(x, z) - 0.01F) {
                continue;
            }
            double y = base + depth;
            air.sample(x, y + 0.2, z, scratch);
            lift(x, y + 0.01, z, scratch[0] * 0.7F, 0.006F, scratch[2] * 0.7F, 0.004F, random.next() < 0.5F ? 1 : 2, random.range(25.0F, 50.0F),
                    0.0F);
        }
    }

    /** Strong gusts shake the snow off the tops of crowns now and then. */
    private void shedCrowns(Level level) {
        float windiness = weather.windiness();
        if (windiness < 0.2F) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < 4; n++) {
            float angle = random.next() * Mth.TWO_PI;
            float r = 18.0F * (float) Math.sqrt(random.next());
            double x = camX + Mth.cos(angle) * r;
            double z = camZ + Mth.sin(angle) * r;
            float push = weather.windSpeed * weather.gustAt(x, z);
            if (random.next() > Mth.clamp((push - 0.25F) * 0.15F, 0.0F, 0.06F)) {
                continue;
            }
            int top = air.top(x, z);
            if (top == Integer.MIN_VALUE) {
                continue;
            }
            pos.set(Mth.floor(x), top - 1, Mth.floor(z));
            if (level.getBlockState(pos).is(BlockTags.LEAVES)) {
                shake(level, pos, 0.35F);
            }
        }
    }

    /**
     * Snow shaken off a snowy leaves block - brushed, bumped, broken or hit by a gust: powder puffs off its top and clumps
     * drop out of its underside with a veil of dust sifting after them. Nothing happens to leaves without snow.
     *
     * @param strength 0..1, how hard it was shaken
     */
    public void shake(Level level, BlockPos pos, float strength) {
        if (!settings.powder || !settings.leafSnow || LeafSnow.mask(level, pos) == 0) {
            return;
        }
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        lift(x, pos.getY() + 1.02, z, 0.0F, 0.02F + strength * 0.05F, 0.0F, 0.02F + strength * 0.03F, Math.round(4 + 14 * strength),
                random.range(40.0F, 80.0F), 0.45F);
        lift(x, pos.getY() - 0.1, z, 0.0F, -0.02F, 0.0F, 0.01F, Math.round(6 + 26 * strength), random.range(60.0F, 120.0F), 0.35F);
        int clumps = Math.round(1 + 5 * strength * random.next());
        int room = settings.powderGrains - flakes.count(Flakes.POWDER);
        for (int n = 0; n < Math.min(clumps, room); n++) {
            int i = flakes.add(Flakes.POWDER, pos.getX() + random.range(0.1F, 0.9F), pos.getY() - 0.05, pos.getZ() + random.range(0.1F, 0.9F));
            if (i < 0) {
                return;
            }
            flakes.shape[i] = random.next() < 0.5F ? Flakes.CLUMP : Flakes.CLUMP_ALT;
            flakes.size[i] = random.range(3.0F, 6.0F);
            flakes.fall[i] = random.range(0.08F, 0.12F);
            flakes.sway[i] = random.range(0.001F, 0.004F);
            flakes.swing[i] = random.range(0.1F, 0.3F);
            flakes.phase[i] = random.next() * Mth.TWO_PI;
            flakes.spinRate[i] = random.signed() * 0.2F;
            flakes.vx[i] = random.signed() * 0.01F;
            flakes.vy[i] = -0.03F;
            flakes.vz[i] = random.signed() * 0.01F;
            flakes.life[i] = 100.0F;
            flakes.light[i] = 0xF00000;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A blast of air: an explosion. The air bursts out and up, carrying the falling flakes with it; the
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
        float total = 0.0F;
        for (SnowCover.Blown column : blown) {
            double dx = column.x() - x;
            double dz = column.z() - z;
            double distance = Math.max(0.3, Math.sqrt(dx * dx + dz * dz));
            float out = strength * 0.55F * (float) (1.0 - Math.min(1.0, distance / (radius * 1.5)));
            int count = Math.min(500, 24 + Math.round(column.amount() * 3500.0F));
            lift(column.x(), column.y(), column.z(), (float) (dx / distance) * out, 0.14F + strength * 0.5F, (float) (dz / distance) * out,
                    0.07F + strength * 0.14F, count, 260.0F, 0.4F);
            total += column.amount();
        }
        if (total > 0.01F) {
            // The heart of the burst: a billowing cloud of snow dust over the blast that hangs a while and drifts off.
            lift(x, y, z, 0.0F, 0.1F + strength * 0.25F, 0.0F, 0.16F + strength * 0.2F, Math.min(400, 60 + Math.round(total * 600.0F)),
                    320.0F, 0.85F);
        }
    }

    /**
     * A foot stepped down.
     *
     * @param moveX  movement this tick
     * @param weight 1 for a player; heavier creatures press deeper
     */
    public void step(Level level, double x, double y, double z, float moveX, float moveZ, float radius, float weight) {
        if (!settings.cover || !settings.footprints) {
            return;
        }
        float speed = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        float dirX = speed > 1.0E-4F ? moveX / speed : 0.0F;
        float dirZ = speed > 1.0E-4F ? moveZ / speed : 0.0F;
        // On ice the foot slides on: the faster the step, the longer the scuff.
        float slip = 1.0F + Math.min(1.5F, speed * 5.0F);
        SnowCover.Step step = cover.step(level, x, y, z, dirX, dirZ, slip, radius, weight);
        float depth = step.depth();
        if (!settings.powder || depth <= 0.0F || speed <= 0.05F) {
            return;
        }
        // Even a dusting puffs a little under a boot; deep snow is kicked forward in clumps of powder.
        int count = Math.round(Math.min(14.0F, speed * 30.0F * (0.2F + 0.8F * Math.min(1.0F, depth * 6.0F))));
        switch (step.footing()) {
            case ICE -> lift(x + dirX * radius, y + 0.02, z + dirZ * radius, moveX * 0.9F, 0.02F + speed * 0.06F, moveZ * 0.9F, 0.02F,
                    count, 40.0F);
            case DEEP -> lift(x + dirX * radius, y + 0.08, z + dirZ * radius, moveX * 0.6F, 0.07F + speed * 0.3F, moveZ * 0.6F, 0.04F,
                    count + 4, 70.0F);
            default -> lift(x, y + 0.05, z, moveX * 0.4F, 0.05F + speed * 0.25F, moveZ * 0.4F, 0.03F, count, 60.0F);
        }
    }

    /** A creature landed in the snow from a fall: a deeper print and a puff of powder all around. */
    public void land(Level level, double x, double y, double z, float width, float fallSpeed) {
        if (!settings.cover || !settings.footprints) {
            return;
        }
        float depth = cover.step(level, x, y, z, 0.0F, 0.0F, 1.0F, width * 0.55F, 1.5F).depth();
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
