package com.pockyl.hoarfrost.sim;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * The snow lying on one chunk column: a 64x64 grid of quarter-block cells on the top surface of each block column.
 * A cell knows the height of the surface under it, how much loose powder lies there and how much packed snow
 * (trodden, compressed), and what kind of surface it is.
 */
public final class CoverChunk {
    public static final int SIZE = 64;
    public static final int AREA = SIZE * SIZE;
    /** Render tiles are 16x16 cells (4x4 blocks). */
    public static final int TILE = 16;
    public static final int TILES = SIZE / TILE;
    /** Surface kinds. */
    public static final byte LEAVES = 1;
    public static final byte ON_SNOW = 2;
    private static final int FORMAT = 1;
    /** Depths are stored in 1/4096 of a block. */
    private static final float QUANTUM = 1.0F / 4096.0F;
    /** Changes of depth smaller than this (a fifth of a pixel) are not redrawn on their own. */
    private static final float SHOWN_STEP = 0.012F;

    public final int x;
    public final int z;
    /** Height of the surface under the snow, NaN where snow cannot lie (water, nothing loaded). */
    public final float[] base = new float[AREA];
    public final float[] powder = new float[AREA];
    public final float[] packed = new float[AREA];
    public final byte[] kind = new byte[AREA];
    /** Incremented whenever the look of a tile changes; the renderer rebuilds tiles whose revision moved. */
    public final int[] tileRevision = new int[TILES * TILES];
    /** The depth of each cell as last drawn (set by the renderer): small changes wait until they can be seen. */
    public final float[] shown = new float[AREA];

    boolean surfaces;
    boolean seeded;
    boolean dirty;
    long lastUpdate = Long.MIN_VALUE;

    public CoverChunk(int x, int z) {
        this.x = x;
        this.z = z;
        Arrays.fill(base, Float.NaN);
    }

    public static int index(int localX, int localZ) {
        return localZ << 6 | localX;
    }

    public static int tile(int index) {
        return (index >> 6 >> 4) * TILES + ((index & 63) >> 4);
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

    /** Notes a change of depth in a cell; its tile is redrawn once the change can be seen (or right away). */
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
        for (int i = 0; i < AREA; i++) {
            if (powder[i] + packed[i] > 0.0F) {
                cells++;
            }
        }
        out.writeShort(cells);
        for (int i = 0; i < AREA; i++) {
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
            int i = in.readUnsignedShort() & (AREA - 1);
            chunk.base[i] = in.readFloat();
            chunk.powder[i] = in.readUnsignedShort() * QUANTUM;
            chunk.packed[i] = in.readUnsignedShort() * QUANTUM;
            chunk.kind[i] = in.readByte();
        }
        return chunk;
    }
}
