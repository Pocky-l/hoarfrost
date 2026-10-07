package com.pockyl.hoarfrost.sim;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import com.pockyl.hoarfrost.weather.Noise;

import java.util.Collection;

/**
 * The snow lying on the world, cell by cell, and what happens to it:
 * <ul>
 *   <li>it settles out of the snowfall - more where the air is calm (the lee of walls, trees and hills, the foot of a
 *   wall facing the wind), less where the wind sweeps the ground, unevenly at first;</li>
 *   <li>strong wind lifts the loose powder and carries it in hops downwind until it reaches calm air or a wall, where
 *   it piles up: drifts grow behind obstacles and windward ground is swept bare;</li>
 *   <li>it slides off where it piles too steeply and spills over edges (a cornice holds only a little);</li>
 *   <li>feet press it into packed footprints, explosions blow it away, lamps and fire melt it, and outside snowy land
 *   it melts away.</li>
 * </ul>
 * Depths are in blocks.
 */
public final class SnowCover {
    public static final float CELL = 0.25F;
    /** Deepest snow that settles out of a snowfall on open ground. */
    public static final float NATURAL_DEPTH = SurfaceKind.natural(SurfaceKind.GROUND);
    /** Deepest a drift can grow. */
    public static final float DRIFT_DEPTH = 0.75F;
    /** Snowfall at full strength settles this much per tick (about a pixel a minute). */
    public static final float SNOW_RATE = 7.0E-5F;
    /** Surface wind (blocks per tick) above which loose powder starts to move. */
    public static final float THRESHOLD = 0.14F;
    private static final float TRANSPORT = 6.0E-3F;
    /** Steepest step between neighboring cells of one surface: a little over 40 degrees. */
    public static final float REPOSE = 0.22F;
    /** How much snow stays on the very edge of a drop. */
    public static final float EDGE_DEPTH = 0.16F;
    /** Surfaces further apart than this are different levels (a step, a wall), not one slope. */
    public static final float LEVEL_STEP = 0.3F;
    private static final float MELT_COLD = 1.5E-3F;
    private static final float MELT_LIGHT = 2.5E-4F;
    private static final float MELT_HOT = 0.01F;
    /** Share of the loose snow under a foot that is pushed aside into a rim around the print. */
    private static final float PUSHED = 0.35F;
    private static final int MELT_LIGHT_MIN = 11;
    private static final int MAX_DT = 600;
    private static final int PATCH_SEED = 0x5E0D;

    private final Long2ObjectOpenHashMap<CoverChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final Terrain terrain;
    private final AirField air;
    private final SnowWeather weather;
    private final byte[] kindOut = new byte[1];
    private final int[] heat = new int[256];
    private float transported;

    SnowCover(Terrain terrain, AirField air, SnowWeather weather) {
        this.terrain = terrain;
        this.air = air;
        this.weather = weather;
    }

    public static int index(int cellX, int cellZ) {
        return CoverChunk.index(cellX & 63, cellZ & 63);
    }

    public CoverChunk chunk(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public CoverChunk chunkAtCell(int cellX, int cellZ) {
        return chunks.get(ChunkPos.asLong(cellX >> 6, cellZ >> 6));
    }

    public Collection<CoverChunk> chunks() {
        return chunks.values();
    }

    public void put(CoverChunk chunk) {
        chunks.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
    }

    public CoverChunk remove(int chunkX, int chunkZ) {
        return chunks.remove(ChunkPos.asLong(chunkX, chunkZ));
    }

    public void clear() {
        chunks.clear();
    }

    static int cell(double coordinate) {
        return Mth.floor(coordinate / CELL);
    }

    /** Snow depth at a point, 0 where there is none. */
    public float depthAt(double x, double z) {
        int cx = cell(x);
        int cz = cell(z);
        CoverChunk chunk = chunkAtCell(cx, cz);
        if (chunk == null) {
            return 0.0F;
        }
        return chunk.depth(index(cx, cz));
    }

    /** Height of the snow's surface at a point, NaN where no snow can lie. */
    public float topAt(double x, double z) {
        int cx = cell(x);
        int cz = cell(z);
        CoverChunk chunk = chunkAtCell(cx, cz);
        if (chunk == null) {
            return Float.NaN;
        }
        return chunk.top(index(cx, cz));
    }

    /** Loose powder at a point. */
    public float powderAt(double x, double z) {
        int cx = cell(x);
        int cz = cell(z);
        CoverChunk chunk = chunkAtCell(cx, cz);
        return chunk == null ? 0.0F : chunk.powder[index(cx, cz)];
    }

    /** Total snow lying in the loaded chunks (tests). */
    public double total() {
        double sum = 0.0;
        for (CoverChunk chunk : chunks.values()) {
            for (int i = 0; i < CoverChunk.AREA; i++) {
                sum += chunk.depth(i);
            }
        }
        return sum;
    }

    /** Snow moved by the wind since the last call, in block-depths of cells (debug). */
    public float takeTransported() {
        float value = transported;
        transported = 0.0F;
        return value;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Surfaces
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Finds the surface of every cell. Snow already lying on a cell (loaded from disk) stays only where the surface is
     * still where it was; anything built on it or dug out from under it starts clean.
     */
    public void computeSurfaces(CoverChunk chunk) {
        for (int i = 0; i < CoverChunk.AREA; i++) {
            surface(chunk, i, false);
        }
        chunk.surfaces = true;
        chunk.touchAll();
    }

    private void surface(CoverChunk chunk, int i, boolean notify) {
        double x = (chunk.x * CoverChunk.SIZE + (i & 63) + 0.5) * CELL;
        double z = (chunk.z * CoverChunk.SIZE + (i >> 6) + 0.5) * CELL;
        float old = chunk.base[i];
        double base = terrain.surface(x, z, kindOut);
        float now = (float) base;
        chunk.kind[i] = kindOut[0];
        if (Double.isNaN(base) || Float.isNaN(old) || Math.abs(now - old) > 0.01F) {
            float lost = chunk.depth(i);
            chunk.powder[i] = 0.0F;
            chunk.packed[i] = 0.0F;
            if (notify && (lost > 0.0F || Float.isNaN(old) != Double.isNaN(base))) {
                chunk.changed(i, 1.0F, true);
            }
        }
        chunk.base[i] = now;
    }

    /** A block changed: the cells of its column find their surface again. */
    public void blockChanged(int x, int z) {
        terrain.invalidate(x, z);
        CoverChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null || !chunk.surfaces) {
            return;
        }
        int cellX = x * 4;
        int cellZ = z * 4;
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                surface(chunk, index(cellX + dx, cellZ + dz), true);
            }
        }
        // The neighbors' edges and slopes are drawn against this column.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                CoverChunk near = chunkAtCell(cellX + dx * 4, cellZ + dz * 4);
                if (near != null) {
                    near.changed(index(cellX + dx * 4, cellZ + dz * 4), 1.0F, true);
                }
            }
        }
    }

    /**
     * First sight of a chunk: snowy land gets the snow it would have from earlier snowfalls - a thin, uneven layer,
     * deeper in the lee of the present wind - so the world is not bare where vanilla shows snow.
     */
    public void seed(CoverChunk chunk) {
        for (int i = 0; i < CoverChunk.AREA; i++) {
            if (!chunk.hasSurface(i)) {
                continue;
            }
            int cellX = chunk.x * CoverChunk.SIZE + (i & 63);
            int cellZ = chunk.z * CoverChunk.SIZE + (i >> 6);
            int bx = cellX >> 2;
            int bz = cellZ >> 2;
            if (!terrain.snowy(bx, bz)) {
                continue;
            }
            float patch = Noise.value(cellX / 9.0, cellZ / 9.0, PATCH_SEED) * 0.7F + Noise.unit(cellX, cellZ, PATCH_SEED + 1) * 0.3F;
            float shelter = terrain.shelter(bx, bz);
            float windward = terrain.windward(bx, bz);
            float natural = SurfaceKind.natural(chunk.kind[i]);
            float depth = natural * (0.2F + 0.4F * patch) + 0.16F * shelter + 0.1F * windward;
            chunk.powder[i] = Math.min(depth, cap(chunk.kind[i], shelter, windward) * 0.75F);
        }
        chunk.seeded = true;
        chunk.touchAll();
    }

    /** The deepest snow that settles out of the air on a cell. */
    private static float cap(byte kind, float shelter, float windward) {
        float natural = SurfaceKind.natural(kind);
        if (kind != SurfaceKind.GROUND && kind != SurfaceKind.ON_SNOW) {
            // Crowns, ice and glass do not hold a drift.
            return natural;
        }
        return Math.min(DRIFT_DEPTH, natural * (1.0F + 1.1F * shelter + 0.9F * windward));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Weathering
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Brings a chunk up to date: snow settles, blows away, melts and slides for the time since its last update. Called
     * for every chunk in turn, a few per tick.
     */
    public void update(CoverChunk chunk, long now) {
        if (!chunk.surfaces) {
            return;
        }
        int dt = chunk.lastUpdate == Long.MIN_VALUE ? 1 : (int) Math.min(MAX_DT, Math.max(1, now - chunk.lastUpdate));
        chunk.lastUpdate = now;
        float snow = weather.snow * weather.gust;
        // Lee and windward spots only collect more snow when there is wind to sort it; calm snow falls evenly.
        float sorting = Mth.clamp(weather.windSpeed() / 0.15F, 0.0F, 1.0F);
        for (int b = 0; b < 256; b++) {
            int bx = chunk.x * 16 + (b & 15);
            int bz = chunk.z * 16 + (b >> 4);
            heat[b] = -1;
            int column = CoverChunk.index((b & 15) * 4, (b >> 4) * 4);
            if (chunk.hasSurface(column) || chunk.hasSurface(column + 65)) {
                float top = chunk.hasSurface(column) ? chunk.top(column) : chunk.top(column + 65);
                heat[b] = terrain.blockLight(bx + 0.5, top + 0.1, bz + 0.5);
            }
        }
        for (int i = 0; i < CoverChunk.AREA; i++) {
            if (!chunk.hasSurface(i)) {
                continue;
            }
            int lx = i & 63;
            int lz = i >> 6;
            int block = (lz >> 2) * 16 + (lx >> 2);
            int bx = chunk.x * 16 + (lx >> 2);
            int bz = chunk.z * 16 + (lz >> 2);
            boolean snowy = terrain.snowy(bx, bz);
            float depth = chunk.depth(i);
            // Melting: warm land, lamps and fire.
            float melt = 0.0F;
            if (!snowy) {
                melt += MELT_COLD;
            }
            if (chunk.kind[i] == SurfaceKind.HOT) {
                melt += MELT_HOT;
            }
            int light = Math.max(0, heat[block]);
            if (light >= MELT_LIGHT_MIN) {
                melt += MELT_LIGHT * (light - MELT_LIGHT_MIN + 1);
            }
            if (melt > 0.0F && depth > 0.0F) {
                melt(chunk, i, melt * dt);
                continue;
            }
            if (!snowy) {
                continue;
            }
            float shelter = terrain.shelter(bx, bz) * sorting;
            float windward = terrain.windward(bx, bz) * sorting;
            float wind = air.surfaceSpeed(bx, bz);
            float threshold = THRESHOLD * SurfaceKind.grip(chunk.kind[i]);
            float sweep = Mth.clamp((wind - threshold) / threshold, 0.0F, 1.0F);
            // Settling out of the snowfall, unevenly: a light snowfall dusts the ground in patches first.
            if (snow > 0.0F) {
                int cellX = chunk.x * CoverChunk.SIZE + lx;
                int cellZ = chunk.z * CoverChunk.SIZE + lz;
                float patch = 0.55F + 0.9F * Noise.value(cellX / 7.0, cellZ / 7.0, PATCH_SEED);
                float factor = Math.max(0.0F, 1.0F + 1.3F * shelter + 1.0F * windward - 0.75F * sweep) * patch;
                float cap = cap(chunk.kind[i], shelter, windward);
                if (depth < cap) {
                    float add = Math.min(cap - depth, SNOW_RATE * snow * weather.accumulation * factor * dt);
                    chunk.powder[i] += add;
                    chunk.changed(i, add, false);
                }
            }
            // Wind lifts the loose powder and carries it.
            if (wind > threshold && chunk.powder[i] > 0.002F && chunk.kind[i] != SurfaceKind.LEAVES) {
                float lift = Math.min(chunk.powder[i] * 0.6F, TRANSPORT * wind * wind * (wind - threshold) * dt);
                if (lift > 1.0E-5F) {
                    chunk.powder[i] -= lift;
                    chunk.changed(i, lift, false);
                    carry(chunk.x * CoverChunk.SIZE + lx, chunk.z * CoverChunk.SIZE + lz, chunk.base[i], lift, wind);
                }
            }
        }
        relax(chunk);
    }

    private static void melt(CoverChunk chunk, int i, float amount) {
        float fromPowder = Math.min(chunk.powder[i], amount);
        chunk.powder[i] -= fromPowder;
        float fromPacked = Math.min(chunk.packed[i], (amount - fromPowder) * 0.6F);
        chunk.packed[i] -= fromPacked;
        if (chunk.powder[i] < 1.0E-4F && chunk.packed[i] < 1.0E-4F) {
            chunk.powder[i] = 0.0F;
            chunk.packed[i] = 0.0F;
        }
        chunk.changed(i, fromPowder + fromPacked, false);
    }

    /**
     * Snow lifted off a cell travels downwind in hops of half a block. It settles in the first calm spot (a lee), in
     * front of a wall it runs into, or where its hop ends; snow blown off the edge of loaded land is lost.
     */
    private void carry(int cellX, int cellZ, float base, float amount, float wind) {
        transported += amount;
        float dx = air.dirX();
        float dz = air.dirZ();
        float reach = Math.min(8.0F, 1.0F + 6.0F * (wind - THRESHOLD) / THRESHOLD);
        double x = (cellX + 0.5) * CELL;
        double z = (cellZ + 0.5) * CELL;
        int lastX = cellX;
        int lastZ = cellZ;
        float lastBase = base;
        for (float travelled = 0.5F; travelled <= reach; travelled += 0.5F) {
            int nx = cell(x + dx * travelled);
            int nz = cell(z + dz * travelled);
            if (nx == lastX && nz == lastZ) {
                continue;
            }
            CoverChunk target = chunkAtCell(nx, nz);
            if (target == null) {
                return;
            }
            int t = index(nx, nz);
            if (!target.hasSurface(t) || target.base[t] > lastBase + 0.6F) {
                // A wall or water ahead: the snow piles up at its foot.
                deposit(lastX, lastZ, amount);
                return;
            }
            lastX = nx;
            lastZ = nz;
            lastBase = target.base[t];
            int bx = nx >> 2;
            int bz = nz >> 2;
            if (terrain.shelter(bx, bz) > 0.45F || air.surfaceSpeed(bx, bz) < THRESHOLD * 0.8F) {
                deposit(nx, nz, amount);
                return;
            }
        }
        deposit(lastX, lastZ, amount);
    }

    private void deposit(int cellX, int cellZ, float amount) {
        CoverChunk chunk = chunkAtCell(cellX, cellZ);
        if (chunk == null) {
            return;
        }
        int i = index(cellX, cellZ);
        if (!chunk.hasSurface(i)) {
            return;
        }
        float room = DRIFT_DEPTH - chunk.depth(i);
        if (room <= 0.0F) {
            return;
        }
        float add = Math.min(room, amount);
        chunk.powder[i] += add;
        chunk.changed(i, add, false);
    }

    /**
     * Snow slides where it lies steeper than its angle of repose and spills over edges; one pass over the chunk, the
     * shape settles over a few updates.
     */
    private void relax(CoverChunk chunk) {
        int originX = chunk.x * CoverChunk.SIZE;
        int originZ = chunk.z * CoverChunk.SIZE;
        for (int i = 0; i < CoverChunk.AREA; i++) {
            if (!chunk.hasSurface(i) || chunk.powder[i] <= 0.0F) {
                continue;
            }
            int cellX = originX + (i & 63);
            int cellZ = originZ + (i >> 6);
            for (int n = 0; n < 4; n++) {
                int nx = cellX + (n == 0 ? 1 : n == 1 ? -1 : 0);
                int nz = cellZ + (n == 2 ? 1 : n == 3 ? -1 : 0);
                CoverChunk other = (nx >> 6) == chunk.x && (nz >> 6) == chunk.z ? chunk : chunkAtCell(nx, nz);
                if (other == null) {
                    continue;
                }
                int j = index(nx, nz);
                float top = chunk.top(i);
                if (!other.hasSurface(j) || other.base[j] < chunk.base[i] - LEVEL_STEP) {
                    // An edge: only a little snow holds on the brink, the rest falls down (or into the water).
                    float excess = chunk.powder[i] - Math.max(0.0F, EDGE_DEPTH - chunk.packed[i]);
                    if (excess > 0.001F) {
                        float move = excess * 0.5F;
                        chunk.powder[i] -= move;
                        chunk.changed(i, move, false);
                        if (other.hasSurface(j) && other.depth(j) + move <= DRIFT_DEPTH) {
                            other.powder[j] += move;
                            other.changed(j, move, false);
                        }
                    }
                    continue;
                }
                if (other.base[j] > chunk.base[i] + LEVEL_STEP) {
                    continue;
                }
                float drop = top - other.top(j);
                if (drop > REPOSE) {
                    float move = Math.min(chunk.powder[i], (drop - REPOSE) * 0.5F);
                    chunk.powder[i] -= move;
                    other.powder[j] += move;
                    chunk.changed(i, move, false);
                    other.changed(j, move, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Disturbances
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A foot comes down: the loose snow under it is pressed into packed snow (about a third of its depth) and partly
     * pushed aside into a rim around the print; on ice the foot only scuffs the thin dusting, most of it skids away.
     *
     * @return the powder that was pressed (how deep the foot sank)
     */
    public float press(double x, double z, double footWidth, double footLength, float dirX, float dirZ) {
        float sank = 0.0F;
        float pushed = 0.0F;
        int cells = 0;
        double halfW = footWidth * 0.5;
        double halfL = footLength * 0.5;
        double reach = Math.max(halfW, halfL) + CELL;
        int minX = cell(x - reach);
        int maxX = cell(x + reach);
        int minZ = cell(z - reach);
        int maxZ = cell(z + reach);
        for (int cz = minZ; cz <= maxZ; cz++) {
            for (int cx = minX; cx <= maxX; cx++) {
                double ox = (cx + 0.5) * CELL - x;
                double oz = (cz + 0.5) * CELL - z;
                double along = ox * dirX + oz * dirZ;
                double across = -ox * dirZ + oz * dirX;
                if (Math.abs(along) > halfL + CELL * 0.35 || Math.abs(across) > halfW + CELL * 0.35) {
                    continue;
                }
                CoverChunk chunk = chunkAtCell(cx, cz);
                if (chunk == null) {
                    continue;
                }
                int i = index(cx, cz);
                float powder = chunk.powder[i];
                if (powder <= 0.004F) {
                    continue;
                }
                chunk.powder[i] = 0.0F;
                chunk.packed[i] += powder * SurfaceKind.packing(chunk.kind[i]);
                chunk.changed(i, powder, true);
                pushed += powder * PUSHED;
                sank += powder;
                cells++;
            }
        }
        if (pushed > 0.0F) {
            // The rim: both sides of the print and a little in front of the toe.
            double side = halfW + CELL * 0.7;
            int steps = Math.max(1, (int) Math.ceil(halfL * 2.0 / CELL));
            int rims = steps * 2 + 1;
            float share = pushed / rims;
            for (int k = 0; k < steps; k++) {
                double along = -halfL + (k + 0.5) * (halfL * 2.0 / steps);
                for (int sign = -1; sign <= 1; sign += 2) {
                    deposit(cell(x + dirX * along - dirZ * side * sign), cell(z + dirZ * along + dirX * side * sign), share);
                }
            }
            deposit(cell(x + dirX * (halfL + CELL * 0.7)), cell(z + dirZ * (halfL + CELL * 0.7)), share);
        }
        return cells == 0 ? 0.0F : sank / cells;
    }

    /** Leaves the shape of a foot in the snow it pressed (drawn as a print until new snow fills it in). */
    public void addPrint(double x, double z, float yaw, float size, byte shape) {
        CoverChunk chunk = chunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        float top = topAt(x, z);
        if (chunk == null || Float.isNaN(top)) {
            return;
        }
        chunk.addPrint(x, z, yaw, size, shape, top);
        chunk.changed(index(cell(x), cell(z)), 1.0F, true);
    }

    /**
     * Removes loose snow around a point (an explosion, a landing): all of it in the middle, less towards the radius.
     *
     * @return the removed snow, in block-depths of cells
     */
    public float scour(double x, double y, double z, float radius, float strength) {
        float removed = 0.0F;
        int minX = cell(x - radius);
        int maxX = cell(x + radius);
        int minZ = cell(z - radius);
        int maxZ = cell(z + radius);
        for (int cz = minZ; cz <= maxZ; cz++) {
            for (int cx = minX; cx <= maxX; cx++) {
                double ox = (cx + 0.5) * CELL - x;
                double oz = (cz + 0.5) * CELL - z;
                float d = (float) Math.sqrt(ox * ox + oz * oz) / radius;
                if (d >= 1.0F) {
                    continue;
                }
                CoverChunk chunk = chunkAtCell(cx, cz);
                if (chunk == null) {
                    continue;
                }
                int i = index(cx, cz);
                if (!chunk.hasSurface(i) || Math.abs(chunk.base[i] - y) > radius) {
                    continue;
                }
                float share = Math.min(1.0F, strength * (1.0F - d * d));
                float powder = chunk.powder[i] * share;
                float packed = chunk.packed[i] * share * 0.5F;
                if (powder + packed <= 0.0F) {
                    continue;
                }
                chunk.powder[i] -= powder;
                chunk.packed[i] -= packed;
                chunk.changed(i, powder + packed, true);
                removed += powder + packed;
            }
        }
        return removed;
    }

    /** Adds loose snow at a cell (tests, snow that falls off things). */
    public void addPowder(double x, double z, float amount) {
        deposit(cell(x), cell(z), amount);
    }
}
