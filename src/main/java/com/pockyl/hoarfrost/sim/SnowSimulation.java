package com.pockyl.hoarfrost.sim;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Snow as a near-physical system, independent of rendering so game tests can drive it on a server level:
 * <ul>
 *   <li>flakes are light bodies that follow the {@link AirField} - they sway and tumble, swirl behind walls, rise over
 *   them, part around a running player - and land on whatever they meet, staying there for a moment before they merge
 *   with the snow lying there;</li>
 *   <li>the {@link SnowCover} on the ground grows out of the snowfall, drifts in the wind, slides, melts and takes
 *   footprints;</li>
 *   <li>grains of powder are blown off the ground by strong wind (ground blizzard, whirls), kicked up by feet and
 *   thrown by explosions.</li>
 * </ul>
 */
public final class SnowSimulation {
    private static final int SETTLE_MIN = 14;
    private static final int SETTLE_RANGE = 34;
    private static final float GRAIN_RADIUS = 20.0F;
    private static final int MAX_GRAINS_SHARE = 4;
    /** The dense snow right around the camera: a box this wide, a share of the flakes kept there. */
    private static final float NEAR_RADIUS = 7.0F;
    private static final float NEAR_BELOW = 3.0F;
    private static final float NEAR_ABOVE = 6.0F;
    private static final float NEAR_SHARE = 0.3F;
    /**
     * The snowfall in the middle distance: flakes in a ring this much wider than the simulated air around the camera.
     * They only fall and drift with the wind (no eddies, no collisions but the ground), which is all one can see of
     * them from there, so many of them cost little; they move through the world like the rest, so flying or falling
     * through them looks right.
     */
    private static final float FAR_EXTRA = 30.0F;
    private static final float FAR_BELOW = 12.0F;
    private static final float FAR_ABOVE = 28.0F;

    private final SnowSettings settings;
    private final SnowWeather weather = new SnowWeather();
    private final Terrain terrain = new Terrain();
    private final AirField air = new AirField(terrain, weather);
    private final SnowCover cover = new SnowCover(terrain, air, weather);
    private final FlakePool pool;
    private final FastRandom random = new FastRandom(System.nanoTime());
    private final float[] sample = new float[3];
    private final List<CoverChunk> updateOrder = new ArrayList<>();
    private int coverCursor;
    private int tick;
    private int falling;
    private int nearFalling;
    private int farFalling;
    private int grains;
    private double camX;
    private double camY;
    private double camZ;

    public SnowSimulation(SnowSettings settings) {
        this.settings = settings;
        this.pool = new FlakePool(settings.maxFlakes + settings.farFlakes);
    }

    public SnowSettings settings() {
        return settings;
    }

    public SnowWeather weather() {
        return weather;
    }

    public FlakePool pool() {
        return pool;
    }

    public SnowCover cover() {
        return cover;
    }

    public AirField air() {
        return air;
    }

    public Terrain terrain() {
        return terrain;
    }

    public int fallingCount() {
        return falling;
    }

    public int grainCount() {
        return grains;
    }

    /** Prepares the level queries for this tick (also needed before touching the cover outside of {@link #tick}). */
    public void begin(Level level) {
        terrain.begin(level, tick);
        float speed = weather.windSpeed();
        if (speed > 1.0E-3F) {
            terrain.setWind(weather.windX / speed, weather.windZ / speed);
        }
    }

    /** One tick: the air, the flakes, new flakes, blowing powder and a few cover chunks brought up to date. */
    public void tick(Level level, double cameraX, double cameraY, double cameraZ) {
        tick++;
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        begin(level);
        air.tick(tick, random, cameraX, cameraZ, settings.drifting && settings.cover);
        updateFlakes();
        spawnFlakes();
        if (settings.cover) {
            if (settings.drifting) {
                blowPowder();
                shedCrowns();
            }
            updateCover(level);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Cover chunks
    // ------------------------------------------------------------------------------------------------------------

    /** A cover chunk came into range: find its surfaces and, if it was never seen, give it the snow of the land. */
    public void prepare(CoverChunk chunk) {
        if (!chunk.surfaces) {
            cover.computeSurfaces(chunk);
        }
        if (!chunk.seeded) {
            cover.seed(chunk);
        }
        chunk.lastUpdate = level() == null ? 0 : level().getGameTime();
    }

    private Level level() {
        return terrain.level();
    }

    private void updateCover(Level level) {
        if (tick % 20 == 1 || updateOrder.isEmpty()) {
            updateOrder.clear();
            double radius = settings.coverRadius + 16.0;
            for (CoverChunk chunk : cover.chunks()) {
                double dx = chunk.x * 16 + 8 - camX;
                double dz = chunk.z * 16 + 8 - camZ;
                if (chunk.surfaces && dx * dx + dz * dz <= radius * radius) {
                    updateOrder.add(chunk);
                }
            }
            if (coverCursor >= updateOrder.size()) {
                coverCursor = 0;
            }
        }
        long now = level.getGameTime();
        for (int n = 0; n < settings.coverChunksPerTick && !updateOrder.isEmpty(); n++) {
            if (coverCursor >= updateOrder.size()) {
                coverCursor = 0;
            }
            cover.update(updateOrder.get(coverCursor++), now);
        }
    }

    /** Brings every loaded cover chunk up to date at once (tests). */
    public void updateAllCover(long gameTime) {
        for (CoverChunk chunk : cover.chunks()) {
            cover.update(chunk, gameTime);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Flakes
    // ------------------------------------------------------------------------------------------------------------

    private float radius() {
        return settings.radius;
    }

    private float above() {
        return settings.radius * 0.8F;
    }

    private float below() {
        return settings.radius * 0.6F;
    }

    private void updateFlakes() {
        FlakePool p = pool;
        int fallingNow = 0;
        int nearNow = 0;
        int farNow = 0;
        int grainsNow = 0;
        for (int i = 0, high = p.highWater(); i < high; i++) {
            byte state = p.state[i];
            if (state == FlakePool.FREE) {
                continue;
            }
            p.prevX[i] = p.x[i];
            p.prevY[i] = p.y[i];
            p.prevZ[i] = p.z[i];
            p.prevSpin[i] = p.spin[i];
            if (state == FlakePool.SETTLED) {
                if (--p.life[i] <= 0) {
                    p.remove(i);
                } else if (p.life[i] < 10) {
                    p.alpha[i] = p.life[i] / 10.0F;
                }
                continue;
            }
            if (state == FlakePool.FALLING) {
                fallingNow++;
                if (p.zone[i] == 1) {
                    nearNow++;
                }
            } else {
                grainsNow++;
            }
            if (p.retiring[i]) {
                p.alpha[i] -= 0.07F;
                if (p.alpha[i] <= 0.0F) {
                    p.remove(i);
                    continue;
                }
            } else if (p.alpha[i] < 1.0F) {
                p.alpha[i] = Math.min(1.0F, p.alpha[i] + 0.05F);
            }
            if (p.zone[i] == 2) {
                farNow++;
                moveFar(i);
                continue;
            }
            double x = p.x[i];
            double y = p.y[i];
            double z = p.z[i];
            double dxCam = x - camX;
            double dzCam = z - camZ;
            boolean near = dxCam * dxCam + dzCam * dzCam < 64.0;
            if (near || ((i + tick) & 1) == 0) {
                air.sample(x, y, z, sample);
                p.airX[i] = sample[0];
                p.airY[i] = sample[1];
                p.airZ[i] = sample[2];
            }
            float size = p.size[i];
            float response;
            float targetX = p.airX[i];
            float targetY;
            float targetZ = p.airZ[i];
            if (state == FlakePool.FALLING) {
                // Small flakes follow the air at once, big aggregates are a little lazier and sway wider.
                response = 0.06F + 0.12F / Math.max(1.0F, size);
                p.phase[i] += p.rate[i];
                float amplitude = weather.flutter * (0.6F + 0.2F * size);
                float phase = p.phase[i];
                targetX += Mth.cos(phase) * amplitude;
                targetZ += Mth.sin(phase * 0.77F + i) * amplitude;
                targetY = p.airY[i] - weather.fallSpeed * p.fall[i] + Mth.sin(phase * 1.9F) * amplitude * 0.3F;
                p.spin[i] += p.spinRate[i];
            } else {
                response = 0.3F;
                targetY = p.airY[i] - 0.028F;
                if (--p.life[i] <= 0) {
                    p.retiring[i] = true;
                }
            }
            p.vx[i] += (targetX - p.vx[i]) * response;
            p.vy[i] += (targetY - p.vy[i]) * response;
            p.vz[i] += (targetZ - p.vz[i]) * response;
            double nx = x + p.vx[i];
            double ny = y + p.vy[i];
            double nz = z + p.vz[i];
            if (collide(i, x, y, z, nx, ny, nz)) {
                continue;
            }
            // Wrap around the simulated volume: what leaves it downwind comes back upwind.
            boolean close = p.zone[i] == 1;
            float radius = close ? NEAR_RADIUS : this.radius();
            double ox = nx - camX;
            double oz = nz - camZ;
            boolean wrapped = false;
            if (ox > radius) {
                nx -= radius * 2.0F;
                wrapped = true;
            } else if (ox < -radius) {
                nx += radius * 2.0F;
                wrapped = true;
            }
            if (oz > radius) {
                nz -= radius * 2.0F;
                wrapped = true;
            } else if (oz < -radius) {
                nz += radius * 2.0F;
                wrapped = true;
            }
            if (close ? ny < camY - NEAR_BELOW || ny > camY + NEAR_ABOVE + 2.0 : ny < camY - below() || ny > camY + above() + 6.0) {
                p.remove(i);
                continue;
            }
            if (wrapped) {
                int bx = Mth.floor(nx);
                int bz = Mth.floor(nz);
                if (state == FlakePool.GRAIN || ny < terrain.height(bx, bz) || !terrain.snowy(bx, bz)) {
                    p.remove(i);
                    continue;
                }
                p.prevX[i] = nx - p.vx[i];
                p.prevY[i] = ny - p.vy[i];
                p.prevZ[i] = nz - p.vz[i];
                p.alpha[i] = 0.0F;
            }
            p.x[i] = nx;
            p.y[i] = ny;
            p.z[i] = nz;
            if (((i + tick) & 7) == 0) {
                int bx = Mth.floor(nx);
                int bz = Mth.floor(nz);
                if (state == FlakePool.FALLING && !terrain.snowy(bx, bz)) {
                    // Into land where it rains or never snows: the flake melts away.
                    p.retiring[i] = true;
                }
                p.light[i] = terrain.lightAt(nx, ny, nz);
            }
        }
        falling = fallingNow;
        nearFalling = nearNow;
        farFalling = farNow;
        grains = grainsNow;
    }

    /**
     * Stops a moving flake at what it meets between its old and new position.
     *
     * @return whether the flake landed (or melted) and must not move on
     */
    private boolean collide(int i, double x, double y, double z, double nx, double ny, double nz) {
        FlakePool p = pool;
        int bx = Mth.floor(nx);
        int bz = Mth.floor(nz);
        int top = terrain.height(bx, bz);
        float snowTop = settings.cover ? cover.landingTop(nx, y, nz) : Float.NaN;
        if (!Float.isNaN(snowTop) && ny <= snowTop && y > snowTop - 0.35) {
            settle(i, nx, snowTop + 0.004, nz);
            return true;
        }
        if (ny >= top + 0.05) {
            return false;
        }
        if (terrain.inFluid(nx, ny, nz)) {
            p.remove(i);
            return true;
        }
        double solid = terrain.solidTop(nx, ny, nz);
        if (!Double.isNaN(solid)) {
            if (y >= solid - 0.02) {
                settle(i, nx, solid + 0.004, nz);
            } else {
                // Against a wall: the flake sticks to it for a moment.
                settle(i, x, y, z);
            }
            return true;
        }
        if (p.state[i] == FlakePool.FALLING && terrain.inFoliage(nx, ny, nz) && random.next() < 0.06F * p.size[i]) {
            settle(i, nx, ny, nz);
            return true;
        }
        return false;
    }

    private void settle(int i, double x, double y, double z) {
        FlakePool p = pool;
        if (p.state[i] == FlakePool.GRAIN) {
            if (p.mass[i] > 0.0F && settings.cover) {
                cover.addPowder(x, y, z, p.mass[i]);
            }
            p.remove(i);
            return;
        }
        p.state[i] = FlakePool.SETTLED;
        p.x[i] = x;
        p.y[i] = y;
        p.z[i] = z;
        p.vx[i] = p.vy[i] = p.vz[i] = 0.0F;
        p.life[i] = SETTLE_MIN + (int) (random.next() * SETTLE_RANGE);
        p.light[i] = terrain.lightAt(x, y + 0.05, z);
    }

    private void spawnFlakes() {
        int target = targetFlakes();
        int nearTarget = (int) (target * NEAR_SHARE);
        spawnZone(1, nearFalling, nearTarget);
        spawnZone(0, falling - nearFalling - farFalling, target - nearTarget);
        float amount = weather.snow * weather.density * Mth.clamp(weather.gust, 0.5F, 1.5F) * settings.farDensity;
        spawnZone(2, farFalling, (int) Math.min(settings.farFlakes, settings.farFlakes * amount));
    }

    /**
     * One tick of a flake in the middle distance: it falls and drifts with the wind (a little faster up high), swaying;
     * where it reaches the ground or leaves the ring it starts again at the top on the other side.
     */
    private void moveFar(int i) {
        FlakePool p = pool;
        p.phase[i] += p.rate[i];
        float sway = weather.flutter * Mth.sin(p.phase[i]);
        float gust = 0.85F + 0.3F * Mth.sin(p.phase[i] * 0.13F + i);
        p.vx[i] = weather.windX * gust + sway;
        p.vy[i] = -weather.fallSpeed * p.fall[i];
        p.vz[i] = weather.windZ * gust + sway * 0.6F;
        double nx = p.x[i] + p.vx[i];
        double ny = p.y[i] + p.vy[i];
        double nz = p.z[i] + p.vz[i];
        float reach = settings.radius + FAR_EXTRA;
        double ox = nx - camX;
        double oz = nz - camZ;
        boolean wrapped = false;
        if (Math.abs(ox) > reach) {
            nx -= Math.signum(ox) * reach * 2.0F;
            wrapped = true;
        }
        if (Math.abs(oz) > reach) {
            nz -= Math.signum(oz) * reach * 2.0F;
            wrapped = true;
        }
        int bx = Mth.floor(nx);
        int bz = Mth.floor(nz);
        if (wrapped || ny < camY - FAR_BELOW || ((i + tick) & 7) == 0 && ny < terrain.height(bx, bz)) {
            if (wrapped || ny < terrain.height(bx, bz) || ny < camY - FAR_BELOW) {
                // Landed or left: back in at the top.
                ny = camY + FAR_ABOVE - random.next() * 2.0;
                if (ny < terrain.height(bx, bz) || !terrain.snowy(bx, bz)) {
                    p.remove(i);
                    return;
                }
                p.prevX[i] = nx;
                p.prevY[i] = ny;
                p.prevZ[i] = nz;
                p.alpha[i] = 0.0F;
            }
        }
        p.x[i] = nx;
        p.y[i] = ny;
        p.z[i] = nz;
        if (((i + tick) & 15) == 0) {
            p.light[i] = terrain.lightAt(nx, ny, nz);
        }
    }

    /**
     * Keeps a zone at its number of flakes. When the snow sets in (or the player arrives) the air fills at once, flakes
     * fading in where they are; after that new flakes come in at the top. Extra flakes fade out.
     */
    private void spawnZone(int zone, int count, int target) {
        if (count < target) {
            boolean fill = count < target * 0.6F;
            int n = Math.min(target - count, fill ? Math.max(40, target / 20) : target / 60 + 8);
            for (int k = 0; k < n; k++) {
                spawnFlake(zone, fill);
            }
        } else if (count > target * 1.1F + 20) {
            int retire = (count - target) / 30 + 1;
            for (int k = 0; k < retire; k++) {
                int i = (int) (random.next() * pool.highWater());
                if (pool.state[i] == FlakePool.FALLING && pool.zone[i] == zone) {
                    pool.retiring[i] = true;
                }
            }
        }
    }

    private int targetFlakes() {
        float amount = weather.snow * weather.density * Mth.clamp(weather.gust, 0.5F, 1.5F) * settings.flakeDensity;
        int reserve = settings.cover && settings.drifting ? settings.maxFlakes / MAX_GRAINS_SHARE : 0;
        return (int) Math.min(settings.maxFlakes - reserve, (settings.maxFlakes - reserve) * amount);
    }

    private void spawnFlake(int zone, boolean fill) {
        double x;
        double z;
        double top;
        double bottom;
        if (zone == 2) {
            float reach = settings.radius + FAR_EXTRA;
            x = camX + (random.next() * 2.0F - 1.0F) * reach;
            z = camZ + (random.next() * 2.0F - 1.0F) * reach;
            double dx = x - camX;
            double dz = z - camZ;
            if (dx * dx + dz * dz < settings.radius * settings.radius) {
                // The simulated flakes are there.
                return;
            }
            top = camY + FAR_ABOVE;
            bottom = camY - FAR_BELOW;
        } else if (zone == 1) {
            x = camX + (random.next() * 2.0F - 1.0F) * NEAR_RADIUS;
            z = camZ + (random.next() * 2.0F - 1.0F) * NEAR_RADIUS;
            top = camY + NEAR_ABOVE;
            bottom = camY - NEAR_BELOW;
        } else {
            float radius = settings.radius;
            float angle = random.next() * Mth.TWO_PI;
            float r = radius * Mth.sqrt(random.next());
            x = camX + Mth.cos(angle) * r;
            z = camZ + Mth.sin(angle) * r;
            top = camY + above();
            bottom = camY - below();
        }
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (!terrain.isLoaded(bx, bz) || !terrain.snowy(bx, bz)) {
            return;
        }
        int ground = terrain.height(bx, bz);
        double y;
        if (fill) {
            double low = Math.max(ground + 0.2, bottom);
            if (low >= top) {
                return;
            }
            y = low + (top - low) * random.next();
        } else {
            y = top - random.next() * 2.0;
            if (y < ground) {
                return;
            }
        }
        int i = pool.add(FlakePool.FALLING, x, y, z);
        if (i < 0) {
            return;
        }
        pool.zone[i] = (byte) zone;
        float u = random.next();
        float size = weather.sizeMin + (weather.sizeMax - weather.sizeMin) * u * u;
        pool.fall[i] = 0.82F + 0.36F * random.next();
        pool.phase[i] = random.next() * Mth.TWO_PI;
        pool.rate[i] = 0.05F + 0.09F * random.next();
        // Light, calm snow comes down as single crystals; heavier snow as clumps of them, a blizzard as fine specks.
        float calm = 1.0F - Mth.clamp(weather.windSpeed() / 0.2F, 0.0F, 1.0F);
        float crystals = Mth.clamp(0.8F - weather.density, 0.05F, 0.6F) * calm;
        byte sprite;
        if (random.next() < crystals) {
            sprite = (byte) (2 + (int) (random.next() * 3.0F));
            size = Math.max(size, 2.4F + random.next() * 2.0F);
        } else {
            sprite = (byte) (size > 1.9F ? 1 : 0);
        }
        pool.size[i] = size;
        pool.sprite[i] = sprite;
        pool.spin[i] = pool.prevSpin[i] = random.next() * Mth.TWO_PI;
        pool.spinRate[i] = sprite >= 2 ? (random.next() - 0.5F) * 0.12F : 0.0F;
        pool.vx[i] = weather.windX;
        pool.vy[i] = -weather.fallSpeed;
        pool.vz[i] = weather.windZ;
        pool.airX[i] = weather.windX;
        pool.airZ[i] = weather.windZ;
        pool.light[i] = terrain.lightAt(x, y, z);
    }

    /** A single falling flake of a given size at a position (tests). */
    public int addFlake(double x, double y, double z, float size) {
        int i = pool.add(FlakePool.FALLING, x, y, z);
        if (i >= 0) {
            pool.size[i] = size;
            pool.fall[i] = 1.0F;
            pool.rate[i] = 0.08F;
            pool.alpha[i] = 1.0F;
        }
        return i;
    }

    /** One step of the flakes only, without new ones (tests). */
    public void stepFlakes(Level level, double cameraX, double cameraY, double cameraZ) {
        tick++;
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        begin(level);
        air.tick(tick, random, cameraX, cameraZ, false);
        updateFlakes();
    }

    /** A grain of powder at a position with a velocity, alive for a while. */
    private int grain(double x, double y, double z, float vx, float vy, float vz, int life) {
        return grain(x, y, z, vx, vy, vz, life, 0.6F + random.next() * 0.7F);
    }

    /** A grain or clump of snow of a given size in pixels. */
    private int grain(double x, double y, double z, float vx, float vy, float vz, int life, float size) {
        if (grains >= settings.maxFlakes / MAX_GRAINS_SHARE) {
            return -1;
        }
        int i = pool.add(FlakePool.GRAIN, x, y, z);
        if (i < 0) {
            return -1;
        }
        grains++;
        pool.size[i] = size;
        pool.sprite[i] = (byte) (size > 1.4F ? 1 : 0);
        pool.vx[i] = vx;
        pool.vy[i] = vy;
        pool.vz[i] = vz;
        pool.airX[i] = vx;
        pool.airY[i] = 0.0F;
        pool.airZ[i] = vz;
        pool.life[i] = life;
        pool.alpha[i] = 0.6F;
        pool.light[i] = terrain.lightAt(x, y + 0.2, z);
        return i;
    }

    /**
     * Strong wind over loose snow carries it along the ground in streamers - the ground blizzard - and whirls lift it
     * in spinning columns. Only seen near the camera; the snow it moves is accounted for by the cover itself.
     */
    private void blowPowder() {
        float speed = weather.windSpeed();
        if (speed > SnowCover.THRESHOLD * 0.7F) {
            int samples = (int) Math.min(64, 12 + speed * 140);
            for (int k = 0; k < samples; k++) {
                float angle = random.next() * Mth.TWO_PI;
                float r = GRAIN_RADIUS * Mth.sqrt(random.next());
                double x = camX + Mth.cos(angle) * r;
                double z = camZ + Mth.sin(angle) * r;
                float powder = cover.powderAt(x, z);
                if (powder < 0.004F) {
                    continue;
                }
                int bx = Mth.floor(x);
                int bz = Mth.floor(z);
                float wind = air.surfaceSpeed(bx, bz);
                float excess = (wind - SnowCover.THRESHOLD * 0.85F) / SnowCover.THRESHOLD;
                if (excess <= 0.0F) {
                    continue;
                }
                float top = cover.topAt(x, z);
                int count = 1 + (int) (excess * 3.0F * random.next());
                for (int c = 0; c < count; c++) {
                    grain(x + (random.next() - 0.5F) * 0.5, top + 0.02 + random.next() * 0.15, z + (random.next() - 0.5F) * 0.5,
                            weather.windX * 0.7F, 0.015F + random.next() * 0.05F * (1.0F + excess), weather.windZ * 0.7F,
                            25 + (int) (random.next() * 40));
                }
            }
        }
        for (AirField.Whirl whirl : air.whirls()) {
            float intensity = whirl.intensity();
            for (int c = 0; c < (int) (4 * intensity); c++) {
                float angle = random.next() * Mth.TWO_PI;
                float r = whirl.radius * random.next();
                double x = whirl.x + Mth.cos(angle) * r;
                double z = whirl.z + Mth.sin(angle) * r;
                if (cover.powderAt(x, z) < 0.004F) {
                    continue;
                }
                grain(x, cover.topAt(x, z) + 0.05, z, 0.0F, 0.06F + random.next() * 0.08F, 0.0F, 40 + (int) (random.next() * 50));
            }
        }
    }

    /**
     * Gusts shake the snow off the crowns of trees: a cap of snow on the leaves near the camera lets go now and then
     * and falls through the branches as a shower of clumps.
     */
    private void shedCrowns() {
        float speed = weather.windSpeed();
        if (speed < 0.03F) {
            return;
        }
        for (int k = 0; k < 6; k++) {
            float angle = random.next() * Mth.TWO_PI;
            float r = 16.0F * Mth.sqrt(random.next());
            double x = camX + Mth.cos(angle) * r;
            double z = camZ + Mth.sin(angle) * r;
            int cx = Mth.floor(x / SnowCover.CELL);
            int cz = Mth.floor(z / SnowCover.CELL);
            CoverChunk chunk = cover.chunkAtCell(cx, cz);
            if (chunk == null) {
                continue;
            }
            int i = SnowCover.index(cx, cz);
            if (chunk.kind[i] != SurfaceKind.LEAVES || chunk.powder[i] < 0.02F) {
                continue;
            }
            float gust = speed * air.gust(x, z);
            if (random.next() > (gust - 0.03F) * 0.6F) {
                continue;
            }
            float released = chunk.powder[i] * (0.4F + 0.4F * random.next());
            chunk.powder[i] -= released;
            chunk.changed(i, released, false);
            float top = chunk.top(i);
            int count = (int) Math.min(14, 3 + released * 150.0F);
            for (int c = 0; c < count; c++) {
                int clump = grain(x + (random.next() - 0.5F) * 0.6, top - random.next() * 0.6, z + (random.next() - 0.5F) * 0.6,
                        weather.windX * 0.5F, -0.02F - random.next() * 0.03F, weather.windZ * 0.5F, 200 + (int) (random.next() * 160),
                        1.2F + random.next() * 1.6F);
                if (clump >= 0) {
                    // The snow comes down to the ground under the tree.
                    pool.mass[clump] = released / count;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Disturbances
    // ------------------------------------------------------------------------------------------------------------

    /** Something moved through the air this tick (the falling snow around it is dragged along). */
    public void disturb(double x, double y, double z, double moveX, double moveZ, float width, float height) {
        air.addWake(x, y, z, moveX, moveZ, width * 0.5F + 0.3F, height);
    }

    /**
     * A foot comes down in the snow: a print is pressed beside the body's center line ({@code side} -1 or 1), the snow
     * it pushes aside forms a rim, and the foot kicks up clumps of powder - a few when walking, a spray when running,
     * none when sneaking.
     *
     * @return how deep the foot sank into loose snow (0 when there was none)
     */
    public float step(double x, double y, double z, float dirX, float dirZ, float width, int side, float speed, boolean sneaking) {
        if (!settings.cover || !settings.footprints) {
            return 0.0F;
        }
        // Prints in the style of the game: square to the block grid, on whole pixels, pointing along an axis.
        float axisX = Math.abs(dirX) >= Math.abs(dirZ) ? Math.signum(dirX) : 0.0F;
        float axisZ = axisX == 0.0F ? Math.signum(dirZ) : 0.0F;
        double footX = pixel(x - axisZ * side * width * 0.22);
        double footZ = pixel(z + axisX * side * width * 0.22);
        float top = cover.topNear(footX, y, footZ);
        if (Float.isNaN(top)) {
            return 0.0F;
        }
        float footWidth = Math.max(0.16F, width * 0.3F);
        float footLength = Math.max(0.22F, width * 0.5F);
        float sank = cover.press(footX, y, footZ, footWidth, footLength, axisX, axisZ);
        if (sank > 0.012F) {
            float size = (float) pixel(Math.max(0.3F, width * 0.6F));
            cover.addPrint(footX, y, footZ, (float) Math.atan2(axisZ, axisX), size);
        }
        if (sank > 0.02F && !sneaking) {
            float kick = 0.3F + speed * 6.0F;
            int count = (int) Math.min(40, sank * 90.0F * kick);
            for (int c = 0; c < count; c++) {
                float forward = speed * (0.5F + random.next() * 0.9F);
                grain(footX + (random.next() - 0.5F) * 0.3, top + 0.03, footZ + (random.next() - 0.5F) * 0.3,
                        dirX * forward + (random.next() - 0.5F) * 0.05F, 0.04F + random.next() * 0.06F * kick,
                        dirZ * forward + (random.next() - 0.5F) * 0.05F, 18 + (int) (random.next() * 22),
                        0.8F + random.next() * random.next() * 1.8F);
            }
        }
        return sank;
    }

    /** Snapped to the pixel grid of the blocks (sixteenths). */
    private static double pixel(double value) {
        return Math.round(value * 16.0) / 16.0;
    }

    /** Landing after a fall: a crater pressed into the snow and a puff of powder. */
    public void land(double x, double y, double z, float width, float fallSpeed) {
        if (!settings.cover || !settings.footprints || fallSpeed < 0.25F) {
            return;
        }
        float top = cover.topNear(x, y, z);
        if (Float.isNaN(top)) {
            return;
        }
        float sank = cover.press(x, y, z, width + 0.2, width + 0.2, 1.0F, 0.0F);
        int count = (int) Math.min(40, sank * 120.0F * fallSpeed * 2.0F);
        for (int c = 0; c < count; c++) {
            float angle = random.next() * Mth.TWO_PI;
            float out = 0.04F + random.next() * 0.06F * fallSpeed;
            grain(x + Mth.cos(angle) * width * 0.5, top + 0.03, z + Mth.sin(angle) * width * 0.5, Mth.cos(angle) * out,
                    0.04F + random.next() * 0.08F * fallSpeed, Mth.sin(angle) * out, 25 + (int) (random.next() * 25));
        }
    }

    /** An explosion or wind charge: its air pulse, the snow blown off the ground around it, a cloud of powder. */
    public void explode(double x, double y, double z, float power, boolean windCharge) {
        float radius = windCharge ? Math.min(8.0F, 2.0F + 2.2F * power) : Math.min(16.0F, 2.5F * power);
        air.addBlast(x, y, z, radius, windCharge ? 0.55F : 0.75F, windCharge ? 0.6F : 0.0F);
        if (!settings.cover) {
            return;
        }
        float removed = cover.scour(x, y, z, radius * 0.8F, windCharge ? 0.9F : 1.3F);
        int count = (int) Math.min(settings.maxFlakes / MAX_GRAINS_SHARE, removed * 260.0F);
        for (int c = 0; c < count; c++) {
            float angle = random.next() * Mth.TWO_PI;
            float r = radius * 0.7F * Mth.sqrt(random.next());
            double px = x + Mth.cos(angle) * r;
            double pz = z + Mth.sin(angle) * r;
            float top = cover.topNear(px, y + 1.0, pz);
            double py = Float.isNaN(top) ? y : top + 0.05;
            float out = 0.1F + random.next() * 0.25F;
            grain(px, py, pz, Mth.cos(angle) * out, 0.1F + random.next() * 0.35F, Mth.sin(angle) * out, 50 + (int) (random.next() * 70));
        }
    }

    public void clear() {
        pool.clear();
        cover.clear();
        air.reset();
        updateOrder.clear();
        falling = 0;
        grains = 0;
    }
}
