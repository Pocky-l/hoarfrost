package com.pockyl.hoarfrost.sim;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import com.pockyl.hoarfrost.weather.Noise;
import com.pockyl.hoarfrost.weather.SnowConditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The snow lying around the camera (or in a game test), chunk by chunk.
 *
 * <p>Each surface grows by what falls on it times its deposition (see {@link CoverSurvey}) and levels off at a depth that
 * grows with the deposition: a dusting where little gets in, a few layers on open ground, deep drifts in the lee. Snow
 * melts in warm land (with Serene Seasons: in spring) and near lamps. Feet press dents and pack paths; blasts blow it
 * away.
 */
public final class SnowCover {
    /** Depth on open flat ground after a long calm snowfall, in blocks. */
    public static final float OPEN_DEPTH = 0.32F;
    /** Snow settling per tick on open ground in a normal snowfall, before it levels off. */
    public static final float RATE = 3.0E-5F;
    /** Melting per tick in warm land and near lamps. */
    private static final float MELT = 6.0E-5F;
    /** Ticks between two updates of a chunk. */
    public static final int INTERVAL = 20;
    /** Share of the natural depth already lying on cold ground seen for the first time. */
    private static final float SEED_SHARE = 0.6F;
    private static final int BLAST_SEED = 0x6B1A;

    private final Long2ObjectOpenHashMap<CoverChunk> chunks = new Long2ObjectOpenHashMap<>();
    private int tick;

    public Collection<CoverChunk> chunks() {
        return chunks.values();
    }

    public CoverChunk chunk(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public CoverChunk chunkAt(int blockX, int blockZ) {
        return chunks.get(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
    }

    public void put(CoverChunk chunk) {
        chunks.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
    }

    public CoverChunk remove(int chunkX, int chunkZ) {
        return chunks.remove(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** The depth snow levels off at for a deposition. */
    public static float capacity(float deposition) {
        return OPEN_DEPTH * (float) Math.pow(Math.clamp(deposition, 0.0F, 3.5F), 0.85);
    }

    /**
     * First snow on ground seen for the first time: what a past snowfall would have left on cold land.
     *
     * @param lay false to start bare (game tests)
     */
    public void seed(CoverChunk chunk, boolean lay) {
        if (lay) {
            for (int e = 0; e < CoverChunk.ENTRIES; e++) {
                byte f = chunk.flags[e];
                if (!Float.isNaN(chunk.base[e]) && (f & CoverChunk.COLD) != 0 && (f & CoverChunk.LIT) == 0) {
                    chunk.depth[e] = Math.max(chunk.depth[e], capacity(chunk.calm[e]) * SEED_SHARE);
                }
            }
        }
        chunk.seeded = true;
        chunk.touched();
    }

    /** Lets snow settle and melt on a share of the chunks; every chunk is updated every {@link #INTERVAL} ticks. */
    public void tick(SnowConditions weather) {
        tick++;
        float angle = (float) Math.atan2(weather.windDirZ, weather.windDirX);
        float windiness = weather.windiness();
        for (CoverChunk chunk : chunks.values()) {
            if (chunk.surveyed && Math.floorMod(chunk.x * 7 + chunk.z * 13 + tick, INTERVAL) == 0) {
                update(chunk, weather, angle, windiness, INTERVAL);
            }
        }
    }

    /** Lets {@code ticks} ticks of weather act on a chunk (game tests step whole chunks at once). */
    public void update(CoverChunk chunk, SnowConditions weather, float angle, float windiness, int ticks) {
        float fall = RATE * weather.accumulation * weather.snow * ticks;
        float filled = 0.0F;
        for (int e = 0; e < CoverChunk.ENTRIES; e++) {
            if (Float.isNaN(chunk.base[e])) {
                continue;
            }
            byte f = chunk.flags[e];
            float before = chunk.depth[e];
            float after = before;
            if ((f & CoverChunk.LIT) != 0) {
                // Gone at once around a lamp: melting slowly through the thin, patchy stage would flicker.
                after = 0.0F;
            } else if ((f & CoverChunk.COLD) == 0) {
                after = Math.max(0.0F, before - MELT * ticks * (1.0F + weather.snow));
            } else if (fall > 0.0F) {
                float deposition = chunk.deposition(e, angle, windiness);
                float capacity = capacity(deposition);
                if (capacity > 0.0F && before < capacity) {
                    float added = fall * deposition * (1.0F - before / capacity);
                    after = before + added;
                    chunk.packed[e] = Math.max(0.0F, chunk.packed[e] - added * 14.0F);
                    filled = Math.max(filled, added);
                }
            }
            if (after != before) {
                chunk.depth[e] = after;
                chunk.addChange(Math.abs(after - before));
            }
        }
        if (chunk.dents > 0) {
            chunk.fillDents(Math.max(filled * 1.3F, fall > 0.0F ? 0.0F : 2.0E-5F * ticks));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------------------------

    /** The surface of a column the point stands on or above (within a block), or -1. */
    public int surfaceBelow(CoverChunk chunk, int column, double y) {
        int found = -1;
        float foundBase = -Float.MAX_VALUE;
        for (int s = 0; s < CoverChunk.SURFACES; s++) {
            int e = s * CoverChunk.COLUMNS + column;
            float b = chunk.base[e];
            if (!Float.isNaN(b) && b <= y + 0.05 && b > y - 1.5 - chunk.depth[e] && b > foundBase) {
                found = s;
                foundBase = b;
            }
        }
        return found;
    }

    /** Depth of the snow at a point standing on it, 0 without snow or survey. */
    public float depthAt(double x, double y, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        CoverChunk chunk = chunkAt(bx, bz);
        if (chunk == null || !chunk.surveyed) {
            return 0.0F;
        }
        int column = CoverChunk.index(bx, bz);
        int s = surfaceBelow(chunk, column, y);
        return s < 0 ? 0.0F : chunk.depth[s * CoverChunk.COLUMNS + column];
    }

    /** How trodden the snow at a point is, 0..1. */
    public float packedAt(double x, double y, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        CoverChunk chunk = chunkAt(bx, bz);
        if (chunk == null || !chunk.surveyed) {
            return 0.0F;
        }
        int column = CoverChunk.index(bx, bz);
        int s = surfaceBelow(chunk, column, y);
        return s < 0 ? 0.0F : chunk.packed[s * CoverChunk.COLUMNS + column];
    }

    public float total() {
        float sum = 0.0F;
        for (CoverChunk chunk : chunks.values()) {
            for (int e = 0; e < CoverChunk.ENTRIES; e++) {
                sum += chunk.depth[e];
            }
        }
        return sum;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Changes
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A foot pressed into the snow: a soft dent, and the column a little more trodden.
     *
     * @return the depth of snow stepped into, 0 if none
     */
    public float step(double x, double y, double z, float radius, float weight) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        CoverChunk chunk = chunkAt(bx, bz);
        if (chunk == null || !chunk.surveyed) {
            return 0.0F;
        }
        int column = CoverChunk.index(bx, bz);
        int s = surfaceBelow(chunk, column, y);
        if (s < 0) {
            return 0.0F;
        }
        int e = s * CoverChunk.COLUMNS + column;
        float depth = chunk.depth[e];
        if (depth < 0.012F || (chunk.flags[e] & CoverChunk.CAP) != 0) {
            return 0.0F;
        }
        chunk.addDent((float) x, (float) z, s, radius, Math.min(depth * 0.75F, 0.07F + 0.06F * weight));
        chunk.packed[e] = Math.min(1.0F, chunk.packed[e] + 0.12F * weight);
        return depth;
    }

    /** Snow blown off the ground by a blast: where it was and how much. */
    public record Blown(double x, double y, double z, float amount) {
    }

    /**
     * Blows the snow away within {@code radius} of a point, most at the centre.
     *
     * @return the snow taken, per column
     */
    public List<Blown> blast(double x, double y, double z, float radius, float strength) {
        List<Blown> blown = new ArrayList<>();
        int r = Mth.ceil(radius);
        for (int bz = Mth.floor(z) - r; bz <= Mth.floor(z) + r; bz++) {
            for (int bx = Mth.floor(x) - r; bx <= Mth.floor(x) + r; bx++) {
                CoverChunk chunk = chunkAt(bx, bz);
                if (chunk == null || !chunk.surveyed) {
                    continue;
                }
                int column = CoverChunk.index(bx, bz);
                for (int s = 0; s < CoverChunk.SURFACES; s++) {
                    int e = s * CoverChunk.COLUMNS + column;
                    float b = chunk.base[e];
                    if (Float.isNaN(b) || chunk.depth[e] <= 0.002F) {
                        continue;
                    }
                    double dx = bx + 0.5 - x;
                    double dy = b - y;
                    double dz = bz + 0.5 - z;
                    double distance = Math.sqrt(dx * dx + dy * dy * 1.5 + dz * dz);
                    // A ragged edge: the blast tears the snow away further in some directions than in others.
                    float reach = radius * (0.75F + 0.5F * Noise.unit(bx, bz, BLAST_SEED));
                    if (distance >= reach) {
                        continue;
                    }
                    float t = (float) (1.0 - distance / reach);
                    float share = Math.min(1.0F, strength * t * t * (3.0F - 2.0F * t) * 1.4F);
                    float taken = chunk.depth[e] * share;
                    if (taken <= 0.0005F) {
                        continue;
                    }
                    chunk.depth[e] -= taken;
                    chunk.packed[e] *= 1.0F - share;
                    chunk.touched();
                    blown.add(new Blown(bx + 0.5, b + chunk.depth[e], bz + 0.5, taken));
                }
            }
        }
        return blown;
    }

    /**
     * Checks again where snow falls and stays on one chunk (with seasons, temperate land turns cold in winter and warm in
     * spring) and where lamps melt it (light spreads a moment after a lamp is placed). Call for a few chunks now and then.
     */
    public void refreshClimate(Level level, CoverChunk chunk, BiPredicate<Level, BlockPos> cold) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean changed = false;
        for (int e = 0; e < CoverChunk.ENTRIES; e++) {
            float b = chunk.base[e];
            if (Float.isNaN(b)) {
                continue;
            }
            int column = e % CoverChunk.COLUMNS;
            pos.set(chunk.x * 16 + (column & 15), Mth.floor(b + 0.02F), chunk.z * 16 + (column >> 4));
            chunk.setCold(e, cold.test(level, pos));
            int block = level.getBrightness(LightLayer.BLOCK, pos);
            changed |= chunk.setLight(e, block);
        }
        if (changed) {
            chunk.touched();
        }
    }

    /**
     * A vanilla snow layer grew or shrank (vanilla snowfall adds them all the time): only that column's surface moves,
     * the snow on it keeping the height it reached, instead of surveying the land again.
     *
     * @param newBase the height of the top of what now lies there
     * @return whether a surface was moved
     */
    public boolean snowLayerChanged(int x, int z, float newBase) {
        CoverChunk chunk = chunkAt(x, z);
        if (chunk == null || !chunk.surveyed) {
            return false;
        }
        int column = CoverChunk.index(x, z);
        int best = -1;
        float bestDistance = 1.01F;
        for (int s = 0; s < CoverChunk.SURFACES; s++) {
            int e = s * CoverChunk.COLUMNS + column;
            float b = chunk.base[e];
            if (!Float.isNaN(b) && Math.abs(b - newBase) < bestDistance) {
                bestDistance = Math.abs(b - newBase);
                best = e;
            }
        }
        if (best < 0) {
            return false;
        }
        float top = chunk.base[best] + chunk.depth[best];
        chunk.base[best] = newBase;
        chunk.depth[best] = Math.max(0.0F, top - newBase);
        chunk.touched();
        return true;
    }

    /** Blocks changed around a column: the land is surveyed again wherever the change can matter (the lee reaches far). */
    public void blockChanged(int x, int z) {
        for (int cx = (x - 12) >> 4; cx <= (x + 12) >> 4; cx++) {
            for (int cz = (z - 12) >> 4; cz <= (z + 12) >> 4; cz++) {
                CoverChunk chunk = chunk(cx, cz);
                if (chunk != null) {
                    chunk.invalidateSurvey();
                }
            }
        }
    }
}
