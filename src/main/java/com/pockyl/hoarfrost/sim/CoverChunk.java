package com.pockyl.hoarfrost.sim;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * The snow lying on one chunk column: a 128x128 grid of cells, 8x8 per block, each with up to two surfaces - the top of
 * the column (open to the sky: ground, a roof, a crown) and the ground under it (beneath a crown, an overhang, the
 * roof of a tunnel or cave mouth). A surface knows its height, how much loose powder lies there and how much packed
 * snow (trodden, compressed), and what kind of surface it is. Indices below {@link #AREA} are top surfaces, the
 * index plus {@link #AREA} is the surface under it.
 */
public final class CoverChunk {
    /** Cells per block along each side. */
    public static final int PER_BLOCK = 8;
    public static final int BLOCK_SHIFT = 3;
    public static final int SIZE = 16 * PER_BLOCK;
    public static final int SHIFT = 7;
    public static final int MASK = SIZE - 1;
    public static final int AREA = SIZE * SIZE;
    public static final int CELLS = AREA * 2;
    /** Render tiles are 32x32 cells (4x4 blocks). */
    public static final int TILE = 32;
    private static final int TILE_SHIFT = 5;
    public static final int TILES = SIZE / TILE;
    private static final int FORMAT = 6;
    /** Depths are stored in 1/4096 of a block. */
    private static final float QUANTUM = 1.0F / 4096.0F;
    /** Changes of depth smaller than this (a fifth of a pixel) are not redrawn on their own. */
    private static final float SHOWN_STEP = 0.012F;
    /** No opening to the open air near a sheltered surface. */
    public static final float NO_MOUTH = 99.0F;

    public final int x;
    public final int z;
    /** Height of the surface under the snow, NaN where there is none (water, nothing loaded, no ground below). */
    public final float[] base = new float[CELLS];
    public final float[] powder = new float[CELLS];
    public final float[] packed = new float[CELLS];
    public final byte[] kind = new byte[CELLS];
    /** The depth of each surface as last drawn (set by the renderer): small changes wait until they can be seen. */
    public final float[] shown = new float[CELLS];
    /**
     * Sheltered ground, per block column: share of the snowfall that comes through the crowns above (0 under a solid
     * roof).
     */
    public final float[] through = new float[256];
    /**
     * Sheltered ground, per block column: distance in blocks to the nearest opening at the same level (the edge of an
     * overhang, a tunnel or cave mouth) and the direction from there inwards. Snow blows in from there.
     */
    public final float[] mouth = new float[256];
    public final float[] mouthX = new float[256];
    public final float[] mouthZ = new float[256];
    /** Incremented whenever the look of a tile changes; the renderer rebuilds tiles whose revision moved. */
    public final int[] tileRevision = new int[TILES * TILES];

    boolean surfaces;
    boolean seeded;
    boolean dirty;
    long lastUpdate = Long.MIN_VALUE;

    public CoverChunk(int x, int z) {
        this.x = x;
        this.z = z;
        Arrays.fill(base, Float.NaN);
        Arrays.fill(mouth, NO_MOUTH);
    }

    /** The top surface of a cell. */
    public static int index(int localX, int localZ) {
        return localZ << SHIFT | localX;
    }

    /** The cell (top surface index) of a surface index. */
    public static int cell(int i) {
        return i & (AREA - 1);
    }

    /** The block column (0..255) of a surface index. */
    public static int block(int i) {
        int cell = i & (AREA - 1);
        return (cell >> SHIFT >> BLOCK_SHIFT) * 16 + ((cell & MASK) >> BLOCK_SHIFT);
    }

    public static boolean sheltered(int i) {
        return i >= AREA;
    }

    public static int tile(int index) {
        int cell = index & (AREA - 1);
        return (cell >> SHIFT >> TILE_SHIFT) * TILES + ((cell & MASK) >> TILE_SHIFT);
    }

    public float depth(int i) {
        return powder[i] + packed[i];
    }

    public float top(int i) {
        return base[i] + powder[i] + packed[i];
    }

    public boolean hasSurface(int i) {
        return !Float.isNaN(base[i]);
    }

    /**
     * The surface of a cell whose height is closest to {@code y}, if it is within {@code tolerance}; -1 otherwise.
     */
    public int near(int cell, float y, float tolerance) {
        int best = -1;
        float bestGap = tolerance;
        for (int i = cell; i < CELLS; i += AREA) {
            if (hasSurface(i)) {
                float gap = Math.abs(base[i] - y);
                if (gap <= bestGap) {
                    best = i;
                    bestGap = gap;
                }
            }
        }
        return best;
    }

    /** The surface of a cell whose snow something falling at height {@code y} lands on: the highest top not above it. */
    public int below(int cell, float y) {
        int best = -1;
        float bestTop = Float.NEGATIVE_INFINITY;
        for (int i = cell; i < CELLS; i += AREA) {
            if (hasSurface(i)) {
                float top = top(i);
                if (top <= y && top > bestTop) {
                    best = i;
                    bestTop = top;
                }
            }
        }
        return best;
    }

    /**
     * The surface of a cell that something at height {@code y} stands on: the highest ground not above it (its feet may
     * be deep in the snow on it).
     */
    public int ground(int cell, float y) {
        int best = -1;
        float bestBase = Float.NEGATIVE_INFINITY;
        for (int i = cell; i < CELLS; i += AREA) {
            if (hasSurface(i) && base[i] <= y && base[i] > bestBase) {
                best = i;
                bestBase = base[i];
            }
        }
        return best;
    }

    /** Changes whenever any tile changes. */
    public int revision() {
        int sum = 0;
        for (int t = 0; t < tileRevision.length; t++) {
            sum += tileRevision[t];
        }
        return sum;
    }

    public boolean seeded() {
        return seeded;
    }

    public boolean hasSurfaces() {
        return surfaces;
    }

    /** Notes a change of depth in a surface; its tile is redrawn once the change can be seen (or right away). */
    void changed(int i, float amount, boolean now) {
        dirty = true;
        if (now || Math.abs(powder[i] + packed[i] - shown[i]) > SHOWN_STEP) {
            tileRevision[tile(i)]++;
        }
    }

    void touchAll() {
        for (int t = 0; t < tileRevision.length; t++) {
            tileRevision[t]++;
        }
        dirty = true;
    }

    /** Whether the chunk changed since it was last saved; clears the flag. */
    public boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    public void write(DataOutput out) throws IOException {
        out.writeByte(FORMAT);
        out.writeInt(x);
        out.writeInt(z);
        out.writeBoolean(seeded);
        int cells = 0;
        for (int i = 0; i < CELLS; i++) {
            if (powder[i] + packed[i] > 0.0F) {
                cells++;
            }
        }
        out.writeShort(cells);
        for (int i = 0; i < CELLS; i++) {
            if (powder[i] + packed[i] > 0.0F) {
                out.writeShort(i);
                out.writeFloat(base[i]);
                out.writeShort(quantize(powder[i]));
                out.writeShort(quantize(packed[i]));
                out.writeByte(kind[i]);
            }
        }
    }

    private static int quantize(float depth) {
        return Math.min(0xFFFF, Math.round(depth / QUANTUM));
    }

    /**
     * Reads a saved chunk. Surfaces are not trusted (blocks may have changed while the chunk was not watched): the
     * snow is kept where the surface is still the same when they are computed again.
     */
    public static CoverChunk read(DataInput in) throws IOException {
        int format = in.readByte();
        if (format != FORMAT) {
            throw new IOException("Unknown snow cover format " + format);
        }
        CoverChunk chunk = new CoverChunk(in.readInt(), in.readInt());
        chunk.seeded = in.readBoolean();
        int cells = in.readUnsignedShort();
        for (int n = 0; n < cells; n++) {
            int i = in.readUnsignedShort() & (CELLS - 1);
            chunk.base[i] = in.readFloat();
            chunk.powder[i] = in.readUnsignedShort() * QUANTUM;
            chunk.packed[i] = in.readUnsignedShort() * QUANTUM;
            chunk.kind[i] = in.readByte();
        }
        return chunk;
    }
}
