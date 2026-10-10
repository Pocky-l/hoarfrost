package com.pockyl.hoarfrost.sim;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * The snow lying on one chunk column. Every block column has up to two surfaces snow can lie on: the top one (ground,
 * roof, crown) and one under it (the ground under a crown or an overhang, the floor of a cave mouth). Each surface holds
 * the height it lies at, how deep the snow is, how trodden, and what the land around it does to falling and blowing snow
 * (see {@link CoverSurvey}). Footprints are kept as small dents on top of that.
 */
public final class CoverChunk {
    public static final int SURFACES = 2;
    public static final int TOP = 0;
    public static final int UNDER = 1;
    public static final int COLUMNS = 256;
    public static final int ENTRIES = SURFACES * COLUMNS;
    public static final int DIRECTIONS = 8;
    /** Most dents kept per chunk; the oldest go first. */
    public static final int MAX_DENTS = 96;

    /** Flags of a surface. */
    public static final byte LEAVES = 1;
    /** A narrow top (fence, wall, post): the snow is a small rounded cap on it. */
    public static final byte CAP = 2;
    /** Snow falls here (cold land); otherwise it melts. */
    public static final byte COLD = 4;
    /** Near a lamp: snow melts. */
    public static final byte LIT = 8;

    /** Saved data of older layouts is ignored. */
    private static final int DATA_VERSION = 1;

    public final int x;
    public final int z;
    /** Height of the surface (world y of the top of the block it lies on), or NaN where there is none. */
    final float[] base = new float[ENTRIES];
    final float[] depth = new float[ENTRIES];
    /** How trodden the snow is, 0 (fresh powder) .. 1 (packed path). */
    final float[] packed = new float[ENTRIES];
    final byte[] flags = new byte[ENTRIES];
    /** How much of the falling snow settles here in still air, relative to open flat ground. */
    final float[] calm = new float[ENTRIES];
    /** The same in a full gale blowing towards each of the eight directions (east first, clockwise), in 1/64. */
    final byte[] windy = new byte[ENTRIES * DIRECTIONS];
    /** For caps: the top of the narrow block, in sixteenths: min x, max x, min z, max z. */
    final byte[] capBox = new byte[ENTRIES * 4];
    /** Packed light just above the surface. */
    final int[] light = new int[ENTRIES];

    // Footprints: centre, the surface they are on, radius and how deep they press.
    final float[] dentX = new float[MAX_DENTS];
    final float[] dentZ = new float[MAX_DENTS];
    final float[] dentRadius = new float[MAX_DENTS];
    final float[] dentDepth = new float[MAX_DENTS];
    final byte[] dentSurface = new byte[MAX_DENTS];
    int dents;

    boolean surveyed;
    /** Depth and packing were set (seeded or loaded), so the chunk is not seeded again. */
    boolean seeded;
    /** Bumped whenever the snow changed enough to be drawn again. */
    int revision;
    private float change;
    private boolean dirty;
    /**
     * Snow waiting for the next survey to find the surfaces it belongs to (after blocks changed, or read from disk):
     * the old surface heights, the heights the snow reached and the packing.
     */
    private float[][] carried;

    public CoverChunk(int x, int z) {
        this.x = x;
        this.z = z;
        Arrays.fill(base, Float.NaN);
    }

    public static int index(int localX, int localZ) {
        return (localZ & 15) << 4 | localX & 15;
    }

    public float base(int surface, int column) {
        return base[surface * COLUMNS + column];
    }

    public float depth(int surface, int column) {
        return depth[surface * COLUMNS + column];
    }

    public float packed(int surface, int column) {
        return packed[surface * COLUMNS + column];
    }

    public byte flags(int surface, int column) {
        return flags[surface * COLUMNS + column];
    }

    public int light(int surface, int column) {
        return light[surface * COLUMNS + column];
    }

    public boolean has(int surface, int column) {
        return !Float.isNaN(base[surface * COLUMNS + column]);
    }

    public boolean surveyed() {
        return surveyed;
    }

    public boolean seeded() {
        return seeded;
    }

    public int revision() {
        return revision;
    }

    public int dentCount() {
        return dents;
    }

    public float dentX(int i) {
        return dentX[i];
    }

    public float dentZ(int i) {
        return dentZ[i];
    }

    public float dentRadius(int i) {
        return dentRadius[i];
    }

    public float dentDepth(int i) {
        return dentDepth[i];
    }

    public int dentSurface(int i) {
        return dentSurface[i];
    }

    /** The cap box of a narrow top in blocks: {@code k} = 0 min x, 1 max x, 2 min z, 3 max z. */
    public float capBox(int surface, int column, int k) {
        return capBox[(surface * COLUMNS + column) * 4 + k] / 16.0F;
    }

    /** Deposition at windiness {@code w} for a wind blowing at {@code angle} (radians, 0 = towards +x). */
    float deposition(int entry, float angle, float w) {
        float c = calm[entry];
        if (w <= 0.001F) {
            return c;
        }
        float sector = (float) (angle / (Math.PI * 2.0) * DIRECTIONS);
        sector -= (float) Math.floor(sector / DIRECTIONS) * DIRECTIONS;
        int a = (int) sector;
        float t = sector - a;
        int b = (a + 1) % DIRECTIONS;
        a %= DIRECTIONS;
        float windyA = (windy[entry * DIRECTIONS + a] & 0xFF) / 64.0F;
        float windyB = (windy[entry * DIRECTIONS + b] & 0xFF) / 64.0F;
        return c + (windyA + (windyB - windyA) * t - c) * w;
    }

    /** Sets where snow falls and stays again (seasons change it); returns whether anything changed. */
    boolean setCold(int entry, boolean cold) {
        byte before = flags[entry];
        flags[entry] = (byte) (cold ? before | COLD : before & ~COLD);
        return flags[entry] != before;
    }

    /** Sets the block light at a surface (lamps melt the snow); returns whether anything changed. */
    boolean setLight(int entry, int block) {
        int before = light[entry];
        byte flagsBefore = flags[entry];
        light[entry] = before & ~0xF0 | block << 4;
        flags[entry] = (byte) (block >= 11 ? flagsBefore | LIT : flagsBefore & ~LIT);
        if ((flags[entry] & LIT) != 0) {
            depth[entry] = 0.0F;
        }
        return light[entry] != before || flags[entry] != flagsBefore;
    }

    void addChange(float amount) {
        change += amount;
        dirty = true;
        if (change > 0.006F) {
            change = 0.0F;
            revision++;
        }
    }

    void touched() {
        change = 0.0F;
        dirty = true;
        revision++;
    }

    /** Whether the snow changed since it was last saved; clears the flag. */
    public boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    void addDent(float x, float z, int surface, float radius, float amount) {
        if (dents == MAX_DENTS) {
            // The oldest dent makes room.
            System.arraycopy(dentX, 1, dentX, 0, MAX_DENTS - 1);
            System.arraycopy(dentZ, 1, dentZ, 0, MAX_DENTS - 1);
            System.arraycopy(dentRadius, 1, dentRadius, 0, MAX_DENTS - 1);
            System.arraycopy(dentDepth, 1, dentDepth, 0, MAX_DENTS - 1);
            System.arraycopy(dentSurface, 1, dentSurface, 0, MAX_DENTS - 1);
            dents--;
        }
        dentX[dents] = x;
        dentZ[dents] = z;
        dentRadius[dents] = radius;
        dentDepth[dents] = amount;
        dentSurface[dents] = (byte) surface;
        dents++;
        touched();
    }

    /** Fresh snow fills the dents; a dent that is gone is dropped. */
    void fillDents(float amount) {
        int kept = 0;
        for (int i = 0; i < dents; i++) {
            float left = dentDepth[i] - amount;
            if (left > 0.004F) {
                dentX[kept] = dentX[i];
                dentZ[kept] = dentZ[i];
                dentRadius[kept] = dentRadius[i];
                dentDepth[kept] = left;
                dentSurface[kept] = dentSurface[i];
                kept++;
            }
        }
        if (kept != dents || amount > 0.0F && dents > 0) {
            addChange(amount * 2.0F);
        }
        dents = kept;
    }

    /** Drops the survey (blocks changed); the snow is kept to be matched to the new surfaces. */
    void invalidateSurvey() {
        if (!surveyed) {
            return;
        }
        surveyed = false;
        float[] tops = new float[ENTRIES];
        for (int e = 0; e < ENTRIES; e++) {
            tops[e] = Float.isNaN(base[e]) ? Float.NaN : base[e] + depth[e];
        }
        carried = new float[][] {base.clone(), tops, packed.clone()};
    }

    /**
     * After a survey: carries the snow over from the surfaces before by the height its top reached, so a block placed
     * beside the snow or a vanilla snow layer growing under it does not change how high the snow lies; snow under a
     * block placed on it is gone.
     */
    void carryOver() {
        if (carried == null) {
            return;
        }
        float[] oldBase = carried[0];
        float[] oldTops = carried[1];
        float[] oldPacked = carried[2];
        carried = null;
        for (int column = 0; column < COLUMNS; column++) {
            for (int s = 0; s < SURFACES; s++) {
                int e = s * COLUMNS + column;
                depth[e] = 0.0F;
                packed[e] = 0.0F;
                if (Float.isNaN(base[e])) {
                    continue;
                }
                float best = Float.MAX_VALUE;
                for (int o = 0; o < SURFACES; o++) {
                    int oe = o * COLUMNS + column;
                    if (Float.isNaN(oldBase[oe]) || Float.isNaN(oldTops[oe])) {
                        continue;
                    }
                    float distance = Math.abs(oldBase[oe] - base[e]);
                    if (distance < 1.01F && distance < best && oldTops[oe] > base[e]) {
                        best = distance;
                        depth[e] = oldTops[oe] - base[e];
                        packed[e] = oldPacked[oe];
                    }
                }
            }
        }
        touched();
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(DATA_VERSION);
        for (int e = 0; e < ENTRIES; e++) {
            boolean has = !Float.isNaN(base[e]) && depth[e] > 0.0005F;
            out.writeShort(has ? Math.round(base[e] * 16.0F) : Short.MIN_VALUE);
            if (has) {
                out.writeShort(Math.round(Math.min(depth[e], 30.0F) * 1000.0F));
                out.writeByte(Math.round(packed[e] * 255.0F));
            }
        }
    }

    /** Reads saved snow; it is matched to the surfaces once the chunk is surveyed. */
    public static CoverChunk read(int x, int z, DataInputStream in) throws IOException {
        if (in.readByte() != DATA_VERSION) {
            return null;
        }
        CoverChunk chunk = new CoverChunk(x, z);
        float[] oldBase = new float[ENTRIES];
        float[] tops = new float[ENTRIES];
        float[] oldPacked = new float[ENTRIES];
        for (int e = 0; e < ENTRIES; e++) {
            short b = in.readShort();
            if (b == Short.MIN_VALUE) {
                oldBase[e] = Float.NaN;
                tops[e] = Float.NaN;
                continue;
            }
            oldBase[e] = b / 16.0F;
            tops[e] = oldBase[e] + (in.readShort() & 0xFFFF) / 1000.0F;
            oldPacked[e] = (in.readByte() & 0xFF) / 255.0F;
        }
        chunk.carried = new float[][] {oldBase, tops, oldPacked};
        chunk.seeded = true;
        return chunk;
    }
}
