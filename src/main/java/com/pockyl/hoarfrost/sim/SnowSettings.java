package com.pockyl.hoarfrost.sim;

/**
 * Settings of the simulation as plain fields, so it does not depend on the config classes (game tests set them
 * directly; the client copies them from its config).
 */
public final class SnowSettings {
    /** Most flakes and grains in the air at once. */
    public int maxFlakes = 6000;
    /** Flakes are simulated within this horizontal distance of the camera. */
    public float radius = 22.0F;
    /** Flakes in the middle distance, beyond the simulated ones (they only fall and drift). */
    public int farFlakes = 6000;
    /** Light flakes in the dense stream right around the camera. */
    public int cloudFlakes = 16000;
    /** Multiplier for the number of flakes in the middle distance (0 = none). */
    public float farDensity = 1.0F;
    /** Multiplier for the number of flakes. */
    public float flakeDensity = 1.0F;
    /** Snow settling on the ground. */
    public boolean cover = true;
    /** Wind moves the snow on the ground (drifts) and blows powder along it. */
    public boolean drifting = true;
    /** Feet leave prints in the snow and kick up powder. */
    public boolean footprints = true;
    /** Snow on the ground is drawn and simulated out to this distance. */
    public float coverRadius = 96.0F;
    /** Cover chunks brought up to date per tick. */
    public int coverChunksPerTick = 3;
}
