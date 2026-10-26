package com.pockyl.hoarfrost.sim;

/**
 * Settings of the simulation as plain fields, so it does not depend on the config classes (game tests set them
 * directly; the client copies them from its config).
 */
public final class SnowSettings {
    /** Most simulated flakes in the air around the camera. */
    public int nearFlakes = 7000;
    /** Flakes are simulated within this horizontal distance of the camera. */
    public float radius = 24.0F;
    /** Flakes falling in the middle distance, beyond the simulated ones. */
    public int farFlakes = 10000;
    /** Snow falls in the middle distance out to this distance. */
    public float farRadius = 72.0F;
    /** Light flakes in the stream rushing past the face in storms. */
    public int streamFlakes = 7000;
    /** Most clouds of snow drifting over the land in a storm. */
    public int clouds = 260;
    /** Most grains of powder lifted from the snow at once. */
    public int powderGrains = 6000;
    /** Multiplier for the number of flakes. */
    public float density = 1.0F;
    /** Multiplier for the flakes in the middle distance (0 = none). */
    public float farDensity = 1.0F;
    /** Snow lies on the ground. */
    public boolean cover = true;
    /** Feet leave dents and pack paths. */
    public boolean footprints = true;
    /** Wind, blasts and feet lift powder off the snow. */
    public boolean powder = true;
    /** Snow lies on the leaves in snowy biomes (and can be shaken off). */
    public boolean leafSnow = true;
    /** Snow on the ground is simulated and drawn out to this distance. */
    public float coverRadius = 80.0F;
    /** Time per tick for surveying land and seeding snow, in nanoseconds. */
    public long surveyBudget = 3_000_000L;
    /** New cold land starts with the snow a past snowfall would have left. */
    public boolean seed = true;
    /** Snow falls and lies everywhere (game tests: the test world is warm). */
    public boolean everywhereCold;
}
