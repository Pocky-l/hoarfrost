package com.pockyl.hoarfrost.sim;

import java.util.Arrays;

/**
 * Everything snowy in the air, as parallel arrays (tens of thousands of them are moved every tick):
 * <ul>
 *   <li>{@link #NEAR} flakes around the camera, moved by the air and stopped by the land;</li>
 *   <li>{@link #FAR} flakes in the middle distance, which only fall and drift with the wind;</li>
 *   <li>{@link #STREAM} light flakes rushing past the face in heavy snow and blizzards;</li>
 *   <li>{@link #POWDER} grains of snow lifted off the ground by the wind, blasts and feet, which fall back slowly.</li>
 * </ul>
 */
public final class Flakes {
    public static final byte FREE = 0;
    public static final byte NEAR = 1;
    public static final byte FAR = 2;
    public static final byte STREAM = 3;
    public static final byte POWDER = 4;
    private static final int KINDS = 5;

    // Shapes, one per sprite of the flake texture.
    public static final byte SPECK = 0;
    public static final byte ROUND = 1;
    public static final byte CRYSTAL = 2;
    public static final byte CRYSTAL_ALT = 3;
    public static final byte CLUMP = 4;
    public static final byte CLUMP_ALT = 5;
    public static final byte GRAIN = 6;
    public static final byte PUFF = 7;

    public final int capacity;
    public final double[] x;
    public final double[] y;
    public final double[] z;
    public final double[] prevX;
    public final double[] prevY;
    public final double[] prevZ;
    public final float[] vx;
    public final float[] vy;
    public final float[] vz;
    /** Size in centimetres. */
    public final float[] size;
    /** Opacity now (fading in and out). */
    public final float[] alpha;
    /** Ticks left; negative = no limit. */
    public final float[] life;
    /** Terminal fall speed. */
    public final float[] fall;
    /** Sway: phase, its speed and the amplitude. */
    public final float[] phase;
    public final float[] sway;
    public final float[] swing;
    /** Turn of the sprite and its speed; the previous turn for interpolation. */
    public final float[] spin;
    public final float[] prevSpin;
    public final float[] spinRate;
    public final byte[] kind;
    public final byte[] shape;
    public final int[] light;
    private final int[] free;
    private int freeCount;
    private int highWater;
    private final int[] counts = new int[KINDS];

    public Flakes(int capacity) {
        this.capacity = capacity;
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
        prevX = new double[capacity];
        prevY = new double[capacity];
        prevZ = new double[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        vz = new float[capacity];
        size = new float[capacity];
        alpha = new float[capacity];
        life = new float[capacity];
        fall = new float[capacity];
        phase = new float[capacity];
        sway = new float[capacity];
        swing = new float[capacity];
        spin = new float[capacity];
        prevSpin = new float[capacity];
        spinRate = new float[capacity];
        kind = new byte[capacity];
        shape = new byte[capacity];
        light = new int[capacity];
        free = new int[capacity];
        clear();
    }

    public void clear() {
        Arrays.fill(kind, FREE);
        freeCount = capacity;
        for (int i = 0; i < capacity; i++) {
            free[i] = capacity - 1 - i;
        }
        highWater = 0;
        Arrays.fill(counts, 0);
    }

    /** A new slot of the kind at a position (previous position the same), or -1 when full. */
    public int add(byte k, double px, double py, double pz) {
        if (freeCount == 0) {
            return -1;
        }
        int i = free[--freeCount];
        kind[i] = k;
        x[i] = prevX[i] = px;
        y[i] = prevY[i] = py;
        z[i] = prevZ[i] = pz;
        vx[i] = vy[i] = vz[i] = 0.0F;
        alpha[i] = 0.0F;
        life[i] = -1.0F;
        spin[i] = prevSpin[i] = 0.0F;
        spinRate[i] = 0.0F;
        light[i] = 0xF000F0;
        counts[k]++;
        highWater = Math.max(highWater, i + 1);
        return i;
    }

    public void remove(int i) {
        if (kind[i] == FREE) {
            return;
        }
        counts[kind[i]]--;
        kind[i] = FREE;
        free[freeCount++] = i;
        while (highWater > 0 && kind[highWater - 1] == FREE) {
            highWater--;
        }
    }

    /** One past the highest slot in use. */
    public int highWater() {
        return highWater;
    }

    public int count(byte k) {
        return counts[k];
    }

    public int count() {
        return capacity - freeCount;
    }

    public int room() {
        return freeCount;
    }
}
