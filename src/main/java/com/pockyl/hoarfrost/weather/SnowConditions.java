package com.pockyl.hoarfrost.weather;

/**
 * The weather a snow simulation runs in, set every tick by its owner (the client's weather, a game test or the server).
 * The kind's parameters are blended, so a change of kind glides over several seconds instead of switching; the wind is
 * the large-scale wind of {@link Wind} at the current time.
 */
public final class SnowConditions {
    /** How much snow falls, 0..1 (the world's precipitation level). */
    public float snow;
    /** Game time with the partial tick, in ticks. */
    public double time;

    // The large-scale wind: direction (unit vector), mean speed in blocks per tick, and how far it has carried the air.
    public float windDirX = 1.0F;
    public float windDirZ;
    public float windSpeed;
    /** How far the wind has carried the air, in blocks: gusts and eddies travel with it. */
    public double driftX;
    public double driftZ;

    // Blended parameters of the kind.
    public float density;
    public float sizeMin;
    public float sizeMax;
    public float crystals;
    public float clumps;
    public float grains;
    public float fallSpeed;
    public float flutter;
    public float wind;
    public float gustiness;
    public float turbulence;
    public float visibility;
    public float stream;
    public float clouds;
    public float accumulation;
    public float hush;
    public float breeze;
    public float windSound;
    public float storm;
    public float howl;
    public float gusts;

    public SnowConditions() {
        snapTo(SnowKind.SNOW);
    }

    /** Sets every parameter of the kind at once (game tests and the first tick). */
    public void snapTo(SnowKind kind) {
        blendTo(kind, 1.0F);
    }

    /** Moves every parameter of the kind a step (0..1) towards the kind's values. */
    public void blendTo(SnowKind kind, float step) {
        density = mix(density, kind.density, step);
        sizeMin = mix(sizeMin, kind.sizeMin, step);
        sizeMax = mix(sizeMax, kind.sizeMax, step);
        crystals = mix(crystals, kind.crystals, step);
        clumps = mix(clumps, kind.clumps, step);
        grains = mix(grains, kind.grains, step);
        fallSpeed = mix(fallSpeed, kind.fallSpeed, step);
        flutter = mix(flutter, kind.flutter, step);
        wind = mix(wind, kind.wind, step);
        gustiness = mix(gustiness, kind.gustiness, step);
        turbulence = mix(turbulence, kind.turbulence, step);
        visibility = mix(visibility, kind.visibility, step);
        stream = mix(stream, kind.stream, step);
        clouds = mix(clouds, kind.clouds, step);
        accumulation = mix(accumulation, kind.accumulation, step);
        hush = mix(hush, kind.hush, step);
        breeze = mix(breeze, kind.breeze, step);
        windSound = mix(windSound, kind.windSound, step);
        storm = mix(storm, kind.storm, step);
        howl = mix(howl, kind.howl, step);
        gusts = mix(gusts, kind.gusts, step);
    }

    /**
     * Sets the large-scale wind for a time and moves the air on by one tick.
     *
     * @param precipitation the world's precipitation level: the wind of a snowfall dies down with it
     */
    public void advanceWind(double gameTime, float precipitation) {
        time = gameTime;
        float angle = Wind.angle(gameTime, gustiness);
        windDirX = (float) Math.cos(angle);
        windDirZ = (float) Math.sin(angle);
        windSpeed = Wind.speed(gameTime, wind, gustiness) * (0.25F + 0.75F * precipitation);
        driftX += windDirX * windSpeed;
        driftZ += windDirZ * windSpeed;
    }

    /** Sets a fixed wind (game tests). */
    public void setWind(float dirX, float dirZ, float speed) {
        float length = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        windDirX = length > 0.0F ? dirX / length : 1.0F;
        windDirZ = length > 0.0F ? dirZ / length : 0.0F;
        windSpeed = speed;
    }

    /** How windy it is, 0 (calm) .. 1 (a heavy blizzard's gale). */
    public float windiness() {
        return Math.clamp(windSpeed / 0.75F, 0.0F, 1.0F);
    }

    /** The gust factor at a point: see {@link Wind#gust}. */
    public float gustAt(double x, double z) {
        return Wind.gust(x, z, driftX, driftZ, gustiness);
    }

    private static float mix(float from, float to, float step) {
        return from + (to - from) * step;
    }
}
