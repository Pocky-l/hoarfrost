package com.pockyl.hoarfrost.weather;

import java.util.Locale;

/**
 * The kinds of snowy weather. Each carries everything that makes it feel different: how many flakes, their size, shape
 * and fall, the wind and its gusts, how far one sees, how fast snow settles and what one hears. The client glides from
 * one kind to the next (see {@link SnowConditions}), so a change of weather never pops.
 *
 * <p>Speeds are in blocks per tick (1 block per tick = 20 m/s), flake sizes in centimetres. Flakes are drawn a little
 * larger than life so they read on screen.
 */
public enum SnowKind {
    // Light snow: a few slow crystals in still air, the hush of snowfall and nothing else.
    LIGHT_SNOW(new Builder().density(0.16F).size(1.3F, 2.6F).shapes(0.45F, 0.0F, 0.0F).fall(0.036F).flutter(0.020F)
            .wind(0.018F, 0.15F, 0.35F).visibility(240.0F).stream(0.0F).accumulation(0.3F).hush(0.6F)
            .sound(0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
    // Snow: a steady, quiet snowfall with a soft breeze.
    SNOW(new Builder().density(0.4F).size(1.5F, 3.0F).shapes(0.2F, 0.1F, 0.0F).fall(0.046F).flutter(0.020F)
            .wind(0.055F, 0.3F, 0.45F).visibility(140.0F).stream(0.0F).accumulation(1.0F).hush(0.75F)
            .sound(0.14F, 0.0F, 0.0F, 0.0F, 0.0F)),
    // Heavy snow: the air full of flakes, the land fading into white, a breeze with soft gusts.
    HEAVY_SNOW(new Builder().density(0.78F).size(1.7F, 3.4F).shapes(0.1F, 0.25F, 0.0F).fall(0.052F).flutter(0.018F)
            .wind(0.095F, 0.4F, 0.5F).visibility(64.0F).stream(0.25F).accumulation(1.9F).hush(0.85F)
            .sound(0.22F, 0.1F, 0.0F, 0.0F, 0.15F)),
    // Heavy snow with large flakes: big fluffy clumps tumbling down slowly through calm air, the deepest hush.
    HEAVY_LARGE_FLAKES(new Builder().density(0.6F).size(3.2F, 7.0F).shapes(0.1F, 0.75F, 0.0F).fall(0.034F).flutter(0.03F)
            .wind(0.03F, 0.2F, 0.3F).visibility(56.0F).stream(0.0F).accumulation(2.3F).hush(1.0F)
            .sound(0.07F, 0.0F, 0.0F, 0.0F, 0.0F)),
    // Light blizzard: fine snow driven by a fresh wind, gusts rolling over the land.
    LIGHT_BLIZZARD(new Builder().density(0.62F).size(1.1F, 2.2F).shapes(0.0F, 0.05F, 0.35F).fall(0.065F).flutter(0.012F)
            .wind(0.32F, 0.65F, 0.7F).visibility(72.0F).stream(0.55F).accumulation(1.2F).hush(0.3F)
            .sound(0.15F, 0.45F, 0.1F, 0.0F, 0.5F)),
    // Blizzard: a howling storm, snow streaming past, squalls swallowing the world for a moment.
    BLIZZARD(new Builder().density(0.85F).size(1.0F, 2.0F).shapes(0.0F, 0.0F, 0.5F).fall(0.075F).flutter(0.01F)
            .wind(0.55F, 0.8F, 0.85F).visibility(38.0F).stream(0.85F).accumulation(1.5F).hush(0.15F)
            .sound(0.05F, 0.3F, 0.7F, 0.25F, 0.8F)),
    // Heavy blizzard: a whiteout, the wind roaring and howling, gusts of snow tearing past.
    HEAVY_BLIZZARD(new Builder().density(1.0F).size(0.9F, 1.9F).shapes(0.0F, 0.0F, 0.6F).fall(0.085F).flutter(0.008F)
            .wind(0.85F, 1.0F, 1.0F).visibility(18.0F).stream(1.0F).accumulation(1.7F).hush(0.05F)
            .sound(0.0F, 0.2F, 0.95F, 0.5F, 1.0F));

    private static final SnowKind[] VALUES = values();

    /** Share of the flake budget in use at full snow. */
    public final float density;
    public final float sizeMin;
    public final float sizeMax;
    /** Shares of six-armed crystals, fluffy clumps of crystals and small hard grains; the rest are soft round flakes. */
    public final float crystals;
    public final float clumps;
    public final float grains;
    /** Terminal fall speed in still air. */
    public final float fallSpeed;
    /** How far flakes sway and tumble while falling. */
    public final float flutter;
    /** Mean wind speed. */
    public final float wind;
    /** How strongly the wind rises in gusts and drops in lulls, 0..1. */
    public final float gustiness;
    /** Strength of the eddies that turn the flakes, 0..1. */
    public final float turbulence;
    /** Blocks through which the falling snow hides the land. */
    public final float visibility;
    /** Density of the stream of light flakes rushing past the face, 0..1. */
    public final float stream;
    /** How fast snow settles, relative to a normal snowfall. */
    public final float accumulation;
    /** How much the snowfall hushes the world's sounds, 0..1. */
    public final float hush;
    /** Loudness of the wind layers: a soft breeze, a fresh wind, the roar of a storm, its howl, and how often gusts sweep past. */
    public final float breeze;
    public final float windSound;
    public final float storm;
    public final float howl;
    public final float gusts;

    SnowKind(Builder b) {
        density = b.density;
        sizeMin = b.sizeMin;
        sizeMax = b.sizeMax;
        crystals = b.crystals;
        clumps = b.clumps;
        grains = b.grains;
        fallSpeed = b.fallSpeed;
        flutter = b.flutter;
        wind = b.wind;
        gustiness = b.gustiness;
        turbulence = b.turbulence;
        visibility = b.visibility;
        stream = b.stream;
        accumulation = b.accumulation;
        hush = b.hush;
        breeze = b.breeze;
        windSound = b.windSound;
        storm = b.storm;
        howl = b.howl;
        gusts = b.gusts;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "hoarfrost.snow_kind." + id();
    }

    /** One of the three blizzards. */
    public boolean blizzard() {
        return this == LIGHT_BLIZZARD || this == BLIZZARD || this == HEAVY_BLIZZARD;
    }

    /** The kind with this ordinal, or {@code null} for -1 or an unknown value. */
    public static SnowKind byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    public static SnowKind byId(String id) {
        for (SnowKind kind : VALUES) {
            if (kind.id().equals(id)) {
                return kind;
            }
        }
        return null;
    }

    private static final class Builder {
        float density;
        float sizeMin;
        float sizeMax;
        float crystals;
        float clumps;
        float grains;
        float fallSpeed;
        float flutter;
        float wind;
        float gustiness;
        float turbulence;
        float visibility;
        float stream;
        float accumulation;
        float hush;
        float breeze;
        float windSound;
        float storm;
        float howl;
        float gusts;

        Builder density(float value) {
            density = value;
            return this;
        }

        Builder size(float min, float max) {
            sizeMin = min;
            sizeMax = max;
            return this;
        }

        Builder shapes(float crystalShare, float clumpShare, float grainShare) {
            crystals = crystalShare;
            clumps = clumpShare;
            grains = grainShare;
            return this;
        }

        Builder fall(float value) {
            fallSpeed = value;
            return this;
        }

        Builder flutter(float value) {
            flutter = value;
            return this;
        }

        Builder wind(float speed, float gusts, float eddies) {
            wind = speed;
            gustiness = gusts;
            turbulence = eddies;
            return this;
        }

        Builder visibility(float blocks) {
            visibility = blocks;
            return this;
        }

        Builder stream(float value) {
            stream = value;
            return this;
        }

        Builder accumulation(float value) {
            accumulation = value;
            return this;
        }

        Builder hush(float value) {
            hush = value;
            return this;
        }

        Builder sound(float breezeGain, float windGain, float stormGain, float howlGain, float gustRate) {
            breeze = breezeGain;
            windSound = windGain;
            storm = stormGain;
            howl = howlGain;
            gusts = gustRate;
            return this;
        }
    }
}
