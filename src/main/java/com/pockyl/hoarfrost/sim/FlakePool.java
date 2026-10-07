package com.pockyl.hoarfrost.sim;

/**
 * Every flake and grain of snow in the air, as a structure of arrays with a free list. Indices are stable while an
 * entry lives, so the renderer can read the arrays directly.
 */
public final class FlakePool {
    public static final byte FREE = 0;
    /** A snowflake falling with the air. */
    public static final byte FALLING = 1;
    /** Powder blown or kicked off the ground: small, quick, near the ground, short-lived. */
    public static final byte GRAIN = 2;
    /** Landed and lying on a surface for a moment before it merges with the snow there. */
    public static final byte SETTLED = 3;

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
    /** The air velocity felt last time it was sampled (sampled every other tick away from the camera). */
    public final float[] airX;
    public final float[] airY;
    public final float[] airZ;
    /** Size in pixels (sixteenths of a block). */
    public final float[] size;
    /** Personal fall speed factor: some flakes are denser than others. */
    public final float[] fall;
    /** Phase and rate of the flutter (the sideways sway and tumble). */
    public final float[] phase;
    public final float[] rate;
    /** Rotation in the view plane (radians) and its speed, for the shapes of larger flakes. */
    public final float[] spin;
    public final float[] prevSpin;
    public final float[] spinRate;
    /** 0..1: fades in when born and out when retired or melting. */
    public final float[] alpha;
    public final byte[] state;
    public final byte[] sprite;
    /** True once the flake is being retired (fading out to free its slot). */
    public final boolean[] retiring;
    /** 1 for flakes kept close around the camera (the dense snow right in front of the eyes), 0 for the rest. */
    public final byte[] zone;
    /** The squall of snow a flake flies in (its id), 0 for the rest. */
    public final int[] owner;
    /** Snow a falling clump carries to where it lands (blocks of depth for one cell), 0 for flakes and powder. */
    public final float[] mass;
    /** Ticks left for grains and settled flakes. */
    public final int[] life;
    /** Packed light, refreshed every few ticks. */
    public final int[] light;

    private final int[] free;
    private int freeCount;
    private int highWater;
    private int count;

    public FlakePool(int capacity) {
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
        airX = new float[capacity];
        airY = new float[capacity];
        airZ = new float[capacity];
        size = new float[capacity];
        fall = new float[capacity];
        phase = new float[capacity];
        rate = new float[capacity];
        spin = new float[capacity];
        prevSpin = new float[capacity];
        spinRate = new float[capacity];
        alpha = new float[capacity];
        state = new byte[capacity];
        sprite = new byte[capacity];
        retiring = new boolean[capacity];
        life = new int[capacity];
        zone = new byte[capacity];
        owner = new int[capacity];
        mass = new float[capacity];
        light = new int[capacity];
        free = new int[capacity];
    }

    /** A new entry at a position (at rest, fully transparent), or -1 when the pool is full. */
    public int add(byte kind, double px, double py, double pz) {
        int i;
        if (freeCount > 0) {
            i = free[--freeCount];
        } else if (highWater < capacity) {
            i = highWater++;
        } else {
            return -1;
        }
        count++;
        state[i] = kind;
        x[i] = prevX[i] = px;
        y[i] = prevY[i] = py;
        z[i] = prevZ[i] = pz;
        vx[i] = vy[i] = vz[i] = 0.0F;
        alpha[i] = 0.0F;
        retiring[i] = false;
        life[i] = 0;
        zone[i] = 0;
        owner[i] = 0;
        mass[i] = 0.0F;
        spin[i] = prevSpin[i] = 0.0F;
        spinRate[i] = 0.0F;
        return i;
    }

    public void remove(int i) {
        if (state[i] == FREE) {
            return;
        }
        state[i] = FREE;
        free[freeCount++] = i;
        count--;
    }

    public void clear() {
        for (int i = 0; i < highWater; i++) {
            state[i] = FREE;
        }
        highWater = 0;
        freeCount = 0;
        count = 0;
    }

    /** All live entries have an index below this. */
    public int highWater() {
        return highWater;
    }

    public int count() {
        return count;
    }
}
