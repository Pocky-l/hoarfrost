package com.pockyl.hoarfrost.weather;

import java.util.Locale;

/**
 * The kinds of snowfall. Each carries the look, the wind and the effect on the ground of that snow; the client blends
 * between them so that a change of type never pops.
 *
 * <p>Speeds are in blocks per tick, flake sizes in pixels (sixteenths of a block).
 */
public enum SnowType {
    // density, fall, sizeMin, sizeMax, flutter, wind, gust, visibility, accumulation, hush, curtains
    FLURRIES(0.22F, 0.045F, 1.0F, 1.6F, 0.022F, 0.035F, 0.45F, 300.0F, 0.3F, 0.3F, 0.35F),
    SNOWFALL(0.6F, 0.05F, 1.0F, 2.4F, 0.026F, 0.05F, 0.35F, 170.0F, 1.0F, 0.6F, 0.8F),
    LARGE_FLAKES(0.42F, 0.036F, 2.0F, 4.2F, 0.034F, 0.022F, 0.15F, 130.0F, 1.4F, 0.85F, 0.65F),
    BLIZZARD(1.0F, 0.06F, 0.8F, 1.8F, 0.012F, 0.42F, 0.9F, 34.0F, 1.3F, 0.25F, 1.0F);

    private static final SnowType[] VALUES = values();

    /** Share of the flake budget in use at full snow. */
    public final float density;
    /** Terminal fall speed in still air. */
    public final float fallSpeed;
    public final float sizeMin;
    public final float sizeMax;
    /** How far flakes sway and tumble sideways while falling. */
    public final float flutter;
    /** Base horizontal wind speed. */
    public final float wind;
    /** How strongly intensity and wind vary over time, 0..1. */
    public final float gustiness;
    /** Blocks through which the snow hides the land at full intensity. */
    public final float visibility;
    /** How fast snow settles on the ground, relative to a normal snowfall. */
    public final float accumulation;
    /** How much the snow hushes the world's sounds, 0..1 (a blizzard is loud with wind instead). */
    public final float hush;
    /** Strength of the curtains of snow in the middle distance. */
    public final float curtains;

    SnowType(float density, float fallSpeed, float sizeMin, float sizeMax, float flutter, float wind, float gustiness,
            float visibility, float accumulation, float hush, float curtains) {
        this.density = density;
        this.fallSpeed = fallSpeed;
        this.sizeMin = sizeMin;
        this.sizeMax = sizeMax;
        this.flutter = flutter;
        this.wind = wind;
        this.gustiness = gustiness;
        this.visibility = visibility;
        this.accumulation = accumulation;
        this.hush = hush;
        this.curtains = curtains;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "hoarfrost.snow_type." + id();
    }

    /** The type with this ordinal, or {@code null} for -1 or an unknown value. */
    public static SnowType byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    public static SnowType byId(String id) {
        for (SnowType type : VALUES) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        return null;
    }
}
