package com.pockyl.hoarfrost.sim;

import com.pockyl.hoarfrost.weather.SnowType;

/**
 * The weather the simulation runs in, set every tick by its owner (the client's weather state, or a game test). All
 * values are the blended parameters of the current snow type, so nothing jumps when the type changes.
 */
public final class SnowWeather {
    /** How much snow falls, 0..1 (the world's precipitation level). */
    public float snow;
    /** Gusts: the snowfall's intensity multiplier around 1. */
    public float gust = 1.0F;
    /** Wind in blocks per tick. */
    public float windX;
    public float windZ;
    /** How far the wind has carried the air so far, in blocks (eddies travel with it). */
    public double driftX;
    public double driftZ;
    public float fallSpeed = SnowType.SNOWFALL.fallSpeed;
    public float flutter = SnowType.SNOWFALL.flutter;
    public float sizeMin = SnowType.SNOWFALL.sizeMin;
    public float sizeMax = SnowType.SNOWFALL.sizeMax;
    public float density = SnowType.SNOWFALL.density;
    public float accumulation = SnowType.SNOWFALL.accumulation;
    public float gustiness = SnowType.SNOWFALL.gustiness;

    public float windSpeed() {
        return (float) Math.sqrt(windX * windX + windZ * windZ);
    }

    /** Sets every type parameter at once (game tests and the first tick). */
    public void snapTo(SnowType type) {
        fallSpeed = type.fallSpeed;
        flutter = type.flutter;
        sizeMin = type.sizeMin;
        sizeMax = type.sizeMax;
        density = type.density;
        accumulation = type.accumulation;
        gustiness = type.gustiness;
    }

    /** Moves every type parameter a step towards the type's values. */
    public void blendTo(SnowType type, float step) {
        fallSpeed += (type.fallSpeed - fallSpeed) * step;
        flutter += (type.flutter - flutter) * step;
        sizeMin += (type.sizeMin - sizeMin) * step;
        sizeMax += (type.sizeMax - sizeMax) * step;
        density += (type.density - density) * step;
        accumulation += (type.accumulation - accumulation) * step;
        gustiness += (type.gustiness - gustiness) * step;
    }
}
