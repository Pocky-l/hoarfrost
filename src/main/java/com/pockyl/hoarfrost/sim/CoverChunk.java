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
    private static final int FORMAT = 2;
    /** Most footprints kept per chunk; the oldest make room for new ones. */
    public static final int MAX_PRINTS = 256;
    /** Footprint shapes. */
    public static final byte PRINT_BOOT = 0;
    public static final byte PRINT_PAW = 1;
    public static final byte PRINT_HOOF = 2;
    public static final byte PRINT_BIRD = 3;
    public static final byte PRINT_BLOB = 4;
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

    /**
     * Footprints: position inside the chunk (blocks), heading, size (blocks), shape, and the height of the snow
     * right after the foot left - new snow on top of that fills the print in.
     */
    public final float[] printX = new float[MAX_PRINTS];
    public final float[] printZ = new float[MAX_PRINTS];
    public final float[] printYaw = new float[MAX_PRINTS];
    public final float[] printSize = new float[MAX_PRINTS];
    public final float[] printTop = new float[MAX_PRINTS];
    public final byte[] printShape = new byte[MAX_PRINTS];
    int printCount;
    int printNext;

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

    public int printCount() {
        return printCount;
    }

    /** Adds a footprint at a world position, replacing the oldest when full. */
    void addPrint(double x, double z, float yaw, float size, byte shape, float top) {
        int p = printNext;
        printNext = (printNext + 1) % MAX_PRINTS;
        printCount = Math.min(MAX_PRINTS, printCount + 1);
        printX[p] = (float) (x - this.x * 16);
        printZ[p] = (float) (z - this.z * 16);
        printYaw[p] = yaw;
        printSize[p] = size;
        printShape[p] = shape;
        printTop[p] = top;
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
        out.writeShort(printCount);
        for (int n = 0; n < printCount; n++) {
            int p = Math.floorMod(printNext - printCount + n, MAX_PRINTS);
            out.writeFloat(printX[p]);
            out.writeFloat(printZ[p]);
            out.writeFloat(printYaw[p]);
            out.writeFloat(printSize[p]);
            out.writeFloat(printTop[p]);
            out.writeByte(printShape[p]);
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
        int prints = Math.min(MAX_PRINTS, in.readUnsignedShort());
        for (int p = 0; p < prints; p++) {
            chunk.printX[p] = in.readFloat();
            chunk.printZ[p] = in.readFloat();
            chunk.printYaw[p] = in.readFloat();
            chunk.printSize[p] = in.readFloat();
            chunk.printTop[p] = in.readFloat();
            chunk.printShape[p] = in.readByte();
        }
        chunk.printCount = prints;
        chunk.printNext = prints % MAX_PRINTS;
        return chunk;
    }
}
