package com.pockyl.hoarfrost.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import com.pockyl.hoarfrost.weather.Noise;
import com.pockyl.hoarfrost.weather.SnowConditions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The air the snow moves in, around a centre (the camera). The large-scale wind of {@link SnowConditions} meets the land:
 * <ul>
 *   <li>near the ground it slows down (a boundary layer), so snow skims slowly over the snow and races higher up;</li>
 *   <li>it rises over the windward side of hills and walls and drops behind them;</li>
 *   <li>behind an obstacle it is calm, with an eddy rolling back towards the obstacle near the ground - snow curls into
 *   the lee and settles there;</li>
 *   <li>it is pushed aside around obstacles;</li>
 *   <li>under crowns and roofs and in caves only a little of it gets in;</li>
 *   <li>gusts and lulls roll through it (see {@link com.pockyl.hoarfrost.weather.Wind#gust}), and eddies carried along
 *   with it turn the flakes;</li>
 *   <li>blasts (wind charges, explosions) burst outwards and upwards, and moving creatures drag the air along.</li>
 * </ul>
 * The land is sampled from heightmaps into a grid around the centre that is refreshed as the centre moves and the wind
 * turns; queries are then a few array lookups.
 */
public final class AirFlow {
    /** Half size of the land grid in blocks. */
    public static final int HALF = 76;
    private static final int SIZE = HALF * 2;
    private static final int SHADOW_REACH = 12;
    /** Ticks between refreshes of the land grid. */
    private static final int REFRESH = 20;
    // The eddy grid: cells of EDDY_CELL blocks around the centre.
    private static final int EDDY_CELL = 4;
    private static final int EDDY_HALF_XZ = 9;
    private static final int EDDY_HALF_Y = 5;
    private static final int EDDY_X = EDDY_HALF_XZ * 2 + 1;
    private static final int EDDY_Y = EDDY_HALF_Y * 2 + 1;
    private static final int EDDY_SEED = 0x7A11;
    private static final float LOG_TOP = (float) Math.log1p(10.0 / 0.35);

    private final int[] heightBlocking = new int[SIZE * SIZE];
    private final int[] heightGround = new int[SIZE * SIZE];
    /** Top of the calm zone behind upwind obstacles at each column. */
    private final float[] shadow = new float[SIZE * SIZE];
    /** Rise of the land along the wind (positive on windward slopes). */
    private final float[] slope = new float[SIZE * SIZE];
    /** Rise of the land across the wind (to the left of it): the air is pushed the other way. */
    private final float[] side = new float[SIZE * SIZE];
    private final float[] smooth = new float[SIZE * SIZE];
    /** Whether snow falls on a column (sampled per 4x4 blocks). */
    private final boolean[] snowy = new boolean[(SIZE / 4) * (SIZE / 4)];
    private final float[] eddies = new float[EDDY_X * EDDY_Y * EDDY_X * 3];
    private final List<Blast> blasts = new ArrayList<>();
    private final List<Wake> wakes = new ArrayList<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private int originX = Integer.MIN_VALUE;
    private int originZ;
    private int eddyOriginX;
    private int eddyOriginY;
    private int eddyOriginZ;
    private float gridDirX = Float.NaN;
    private float gridDirZ;
    private int sinceRefresh;
    private SnowConditions weather;

    /** A burst of air outwards from a point, fading over a second or two. */
    private static final class Blast {
        final double x;
        final double y;
        final double z;
        final float radius;
        final float strength;
        int age;

        Blast(double x, double y, double z, float radius, float strength) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.strength = strength;
        }
    }

    /** Air dragged along by a moving creature, for one tick. */
    private record Wake(double x, double y, double z, float vx, float vy, float vz, float radius, float height) {
    }

    /**
     * Brings the land grid and the eddies up to date. Call once per tick before sampling.
     *
     * @param cold where snow falls (land grid only)
     */
    public void update(Level level, SnowConditions weather, double centerX, double centerY, double centerZ,
            BiPredicate<Level, BlockPos> cold) {
        this.weather = weather;
        int cx = Mth.floor(centerX);
        int cz = Mth.floor(centerZ);
        sinceRefresh++;
        boolean moved = originX == Integer.MIN_VALUE || Math.abs(cx - HALF - originX) > 8 || Math.abs(cz - HALF - originZ) > 8;
        boolean turned = Float.isNaN(gridDirX) || gridDirX * weather.windDirX + gridDirZ * weather.windDirZ < 0.996F;
        if (moved || turned || sinceRefresh >= REFRESH) {
            boolean heights = moved || sinceRefresh >= REFRESH;
            sinceRefresh = 0;
            refresh(level, moved ? cx - HALF : originX, moved ? cz - HALF : originZ, heights, cold);
        }
        updateEddies(centerX, centerY, centerZ);
        for (int i = blasts.size() - 1; i >= 0; i--) {
            if (++blasts.get(i).age > 50) {
                blasts.remove(i);
            }
        }
        wakes.clear();
    }

    private void refresh(Level level, int newOriginX, int newOriginZ, boolean heights, BiPredicate<Level, BlockPos> cold) {
        originX = newOriginX;
        originZ = newOriginZ;
        if (heights) {
            for (int gz = 0; gz < SIZE; gz++) {
                for (int gx = 0; gx < SIZE; gx++) {
                    int x = originX + gx;
                    int z = originZ + gz;
                    heightBlocking[gz * SIZE + gx] = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    heightGround[gz * SIZE + gx] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                }
            }
            for (int sz = 0; sz < SIZE / 4; sz++) {
                for (int sx = 0; sx < SIZE / 4; sx++) {
                    int x = originX + sx * 4 + 2;
                    int z = originZ + sz * 4 + 2;
                    pos.set(x, heightBlocking[(sz * 4 + 2) * SIZE + sx * 4 + 2], z);
                    snowy[sz * (SIZE / 4) + sx] = cold.test(level, pos);
                }
            }
        }
        gridDirX = weather.windDirX;
        gridDirZ = weather.windDirZ;
        // Obstacles for the wind: leaves let it through in part. Smoothed 3x3 for slopes.
        for (int gz = 0; gz < SIZE; gz++) {
            for (int gx = 0; gx < SIZE; gx++) {
                float sum = 0.0F;
                int n = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = gx + dx;
                        int z = gz + dz;
                        if (x >= 0 && z >= 0 && x < SIZE && z < SIZE) {
                            sum += obstacle(x, z);
                            n++;
                        }
                    }
                }
                smooth[gz * SIZE + gx] = sum / n;
            }
        }
        float dx = gridDirX;
        float dz = gridDirZ;
        for (int gz = 0; gz < SIZE; gz++) {
            for (int gx = 0; gx < SIZE; gx++) {
                int i = gz * SIZE + gx;
                float top = -Float.MAX_VALUE;
                for (int k = 1; k <= SHADOW_REACH; k++) {
                    int ux = Math.round(gx - dx * k);
                    int uz = Math.round(gz - dz * k);
                    if (ux < 0 || uz < 0 || ux >= SIZE || uz >= SIZE) {
                        break;
                    }
                    // The calm zone behind an obstacle sinks about one block per five downwind.
                    top = Math.max(top, obstacle(ux, uz) - k * 0.2F);
                }
                shadow[i] = top;
                slope[i] = (smoothAt(gx + dx, gz + dz) - smoothAt(gx - dx, gz - dz)) * 0.5F;
                side[i] = (smoothAt(gx - dz, gz + dx) - smoothAt(gx + dz, gz - dx)) * 0.5F;
            }
        }
    }

    private float obstacle(int gx, int gz) {
        int i = gz * SIZE + gx;
        return heightGround[i] + (heightBlocking[i] - heightGround[i]) * 0.55F;
    }

    private float smoothAt(float gx, float gz) {
        int x = Math.clamp(Math.round(gx), 0, SIZE - 1);
        int z = Math.clamp(Math.round(gz), 0, SIZE - 1);
        return smooth[z * SIZE + x];
    }

    private void updateEddies(double centerX, double centerY, double centerZ) {
        eddyOriginX = Math.floorDiv(Mth.floor(centerX), EDDY_CELL) - EDDY_HALF_XZ;
        eddyOriginY = Math.floorDiv(Mth.floor(centerY), EDDY_CELL) - EDDY_HALF_Y;
        eddyOriginZ = Math.floorDiv(Mth.floor(centerZ), EDDY_CELL) - EDDY_HALF_XZ;
        double t = weather.time / 70.0;
        for (int ez = 0; ez < EDDY_X; ez++) {
            for (int ey = 0; ey < EDDY_Y; ey++) {
                for (int ex = 0; ex < EDDY_X; ex++) {
                    // Fixed to the moving air, and slowly changing in it.
                    double x = ((eddyOriginX + ex) * EDDY_CELL - weather.driftX) / 7.0;
                    double y = (eddyOriginY + ey) * EDDY_CELL / 7.0 + t;
                    double z = ((eddyOriginZ + ez) * EDDY_CELL - weather.driftZ) / 7.0;
                    int i = ((ez * EDDY_Y + ey) * EDDY_X + ex) * 3;
                    eddies[i] = Noise.value(x, y, z, EDDY_SEED) * 2.0F - 1.0F;
                    eddies[i + 1] = (Noise.value(x, y, z, EDDY_SEED + 1) * 2.0F - 1.0F) * 0.6F;
                    eddies[i + 2] = Noise.value(x, y, z, EDDY_SEED + 2) * 2.0F - 1.0F;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Disturbances
    // ------------------------------------------------------------------------------------------------------------

    /** A burst of air: wind charges and explosions. */
    public void blast(double x, double y, double z, float radius, float strength) {
        blasts.add(new Blast(x, y, z, radius, strength));
    }

    /** A creature moving through the air this tick. */
    public void wake(double x, double y, double z, float vx, float vy, float vz, float radius, float height) {
        if (vx * vx + vy * vy + vz * vz > 1.0E-4F) {
            wakes.add(new Wake(x, y, z, vx, vy, vz, radius, height));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------------------------

    private int index(double x, double z) {
        int gx = Mth.floor(x) - originX;
        int gz = Mth.floor(z) - originZ;
        return gx < 0 || gz < 0 || gx >= SIZE || gz >= SIZE ? -1 : gz * SIZE + gx;
    }

    /** Height of the top of what stands at a column (leaves included), or {@code Integer.MIN_VALUE} outside the grid. */
    public int top(double x, double z) {
        int i = index(x, z);
        return i < 0 ? Integer.MIN_VALUE : heightBlocking[i];
    }

    /** Whether snow falls on a column (false outside the grid). */
    public boolean snowy(double x, double z) {
        int gx = Mth.floor(x) - originX;
        int gz = Mth.floor(z) - originZ;
        if (gx < 0 || gz < 0 || gx >= SIZE || gz >= SIZE) {
            return false;
        }
        return snowy[(gz >> 2) * (SIZE / 4) + (gx >> 2)];
    }

    /** Whether a point is in the grid. */
    public boolean covers(double x, double z) {
        return index(x, z) >= 0;
    }

    /**
     * The velocity of the air at a point, in blocks per tick.
     *
     * @param out receives x, y, z
     */
    public void sample(double x, double y, double z, float[] out) {
        SnowConditions w = weather;
        float gust = w.gustAt(x, z);
        float speed = w.windSpeed * gust;
        float dirX = w.windDirX;
        float dirZ = w.windDirZ;
        float vx;
        float vy = 0.0F;
        float vz;
        int i = index(x, z);
        if (i < 0) {
            vx = dirX * speed;
            vz = dirZ * speed;
        } else {
            float above = (float) (y - heightBlocking[i]);
            float factor;
            if (above < 0.0F) {
                // Under a crown, a roof or in a cave: only a little of the wind gets in.
                boolean canopy = y >= heightGround[i];
                factor = canopy ? 0.3F : 0.18F;
            } else {
                factor = Math.clamp((float) Math.log1p(above / 0.35F) / LOG_TOP, 0.18F, 1.0F);
            }
            float back = 0.0F;
            float sheltered = (float) (shadow[i] - y);
            if (sheltered > 0.0F) {
                float s = Math.min(1.0F, sheltered / 1.5F);
                s = s * s * (3.0F - 2.0F * s);
                factor *= 1.0F - 0.78F * s;
                // The eddy behind the obstacle rolls back towards it near the ground.
                back = 0.22F * s * (above < 2.0F ? 1.0F : 0.5F);
            }
            float horizontal = speed * (factor - back);
            vx = dirX * horizontal;
            vz = dirZ * horizontal;
            if (above > -0.5F) {
                // Up the windward side, down the lee.
                vy += speed * Math.clamp(slope[i], -1.5F, 2.0F) * 0.55F * (float) Math.exp(-Math.max(above, 0.0F) / 2.5F);
                // Aside around obstacles.
                float push = -Math.clamp(side[i], -2.0F, 2.0F) * speed * 0.3F * (float) Math.exp(-Math.max(above, 0.0F) / 3.0F);
                vx += -dirZ * push;
                vz += dirX * push;
            }
        }
        // Eddies, stronger in a storm and when the wind blows harder.
        float eddy = (0.004F + speed * 0.4F) * w.turbulence;
        if (eddy > 0.0F) {
            sampleEddies(x, y, z, out);
            vx += out[0] * eddy;
            vy += out[1] * eddy;
            vz += out[2] * eddy;
        }
        for (Blast blast : blasts) {
            double dx = x - blast.x;
            double dy = y - blast.y;
            double dz = z - blast.z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            float reach = blast.radius * 2.5F;
            if (distanceSq >= reach * reach || distanceSq < 1.0E-6) {
                continue;
            }
            float distance = (float) Math.sqrt(distanceSq);
            float fall = 1.0F - distance / reach;
            float push = blast.strength * fall * fall * (float) Math.exp(-blast.age / 6.0F) / distance;
            vx += (float) dx * push;
            vy += (float) dy * push + push * distance * 0.5F;
            vz += (float) dz * push;
        }
        for (Wake wake : wakes) {
            double dx = x - wake.x;
            double dz = z - wake.z;
            double dy = y - wake.y;
            if (dy < -0.3 || dy > wake.height + 0.3) {
                continue;
            }
            double distanceSq = dx * dx + dz * dz;
            float reach = wake.radius + 0.8F;
            if (distanceSq >= reach * reach) {
                continue;
            }
            float fall = 1.0F - (float) Math.sqrt(distanceSq) / reach;
            vx += wake.vx * 0.7F * fall;
            vy += wake.vy * 0.4F * fall + 0.02F * fall * Math.min(1.0F, Math.abs(wake.vx) + Math.abs(wake.vz));
            vz += wake.vz * 0.7F * fall;
        }
        out[0] = vx;
        out[1] = vy;
        out[2] = vz;
    }

    /** Eddy vector at a point (each component about -1..1), interpolated in the eddy grid. */
    private void sampleEddies(double x, double y, double z, float[] out) {
        float fx = (float) (x / EDDY_CELL) - eddyOriginX;
        float fy = (float) (y / EDDY_CELL) - eddyOriginY;
        float fz = (float) (z / EDDY_CELL) - eddyOriginZ;
        fx = Math.clamp(fx, 0.0F, EDDY_X - 1.001F);
        fy = Math.clamp(fy, 0.0F, EDDY_Y - 1.001F);
        fz = Math.clamp(fz, 0.0F, EDDY_X - 1.001F);
        int x0 = (int) fx;
        int y0 = (int) fy;
        int z0 = (int) fz;
        float tx = fx - x0;
        float ty = fy - y0;
        float tz = fz - z0;
        for (int c = 0; c < 3; c++) {
            float c000 = eddy(x0, y0, z0, c);
            float c100 = eddy(x0 + 1, y0, z0, c);
            float c010 = eddy(x0, y0 + 1, z0, c);
            float c110 = eddy(x0 + 1, y0 + 1, z0, c);
            float c001 = eddy(x0, y0, z0 + 1, c);
            float c101 = eddy(x0 + 1, y0, z0 + 1, c);
            float c011 = eddy(x0, y0 + 1, z0 + 1, c);
            float c111 = eddy(x0 + 1, y0 + 1, z0 + 1, c);
            float a = c000 + (c100 - c000) * tx;
            float b = c010 + (c110 - c010) * tx;
            float d = c001 + (c101 - c001) * tx;
            float e = c011 + (c111 - c011) * tx;
            float f = a + (b - a) * ty;
            float g = d + (e - d) * ty;
            out[c] = f + (g - f) * tz;
        }
    }

    private float eddy(int ex, int ey, int ez, int component) {
        return eddies[((ez * EDDY_Y + ey) * EDDY_X + ex) * 3 + component];
    }

    /** How fast the wind blows at a point, for sounds and spindrift. */
    public float speedAt(double x, double y, double z, float[] scratch) {
        sample(x, y, z, scratch);
        return (float) Math.sqrt(scratch[0] * scratch[0] + scratch[2] * scratch[2]);
    }
}
