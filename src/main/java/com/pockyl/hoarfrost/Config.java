package com.pockyl.hoarfrost;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The weather itself and what the server does with it. Shared by server and clients: a client on a server without
 * the mod uses its own values for the course of the snowfall.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.translation(key("weather")).push("weather");
    }

    public static final ModConfigSpec.IntValue FLURRIES_WEIGHT = BUILDER
            .comment("Relative chance of light flurries when it starts to snow and every five minutes of a snowfall.")
            .translation(key("flurriesWeight"))
            .defineInRange("flurriesWeight", 3, 0, 100);
    public static final ModConfigSpec.IntValue SNOWFALL_WEIGHT = BUILDER
            .comment("Relative chance of a steady snowfall.")
            .translation(key("snowfallWeight"))
            .defineInRange("snowfallWeight", 4, 0, 100);
    public static final ModConfigSpec.IntValue LARGE_FLAKES_WEIGHT = BUILDER
            .comment("Relative chance of slow, large flakes in calm air.")
            .translation(key("largeFlakesWeight"))
            .defineInRange("largeFlakesWeight", 2, 0, 100);
    public static final ModConfigSpec.IntValue BLIZZARD_WEIGHT = BUILDER
            .comment("Relative chance of a blizzard without a thunderstorm.")
            .translation(key("blizzardWeight"))
            .defineInRange("blizzardWeight", 1, 0, 100);
    public static final ModConfigSpec.BooleanValue THUNDER_BLIZZARD = BUILDER
            .comment("A thunderstorm in snowy land is a blizzard.")
            .translation(key("thunderBlizzard"))
            .define("thunderBlizzard", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("drifts")).push("drifts");
    }

    public static final ModConfigSpec.BooleanValue DRIFTS = BUILDER
            .comment("Server: while it snows with wind, snow layers pile up in the lee of walls, trees and hills and at the foot of "
                    + "walls facing the wind. Changes the world (snow layer blocks); only where the sky is open.")
            .translation(key("driftsEnabled"))
            .define("enabled", true);
    public static final ModConfigSpec.IntValue DRIFT_MAX_LAYERS = BUILDER
            .comment("Highest drift in snow layers (8 = a full block). 4 or less can always be walked over.")
            .translation(key("driftMaxLayers"))
            .defineInRange("maxLayers", 4, 1, 8);
    public static final ModConfigSpec.DoubleValue DRIFT_SPEED = BUILDER
            .comment("How fast drifts grow. 1 = a drift of four layers takes a strong snowstorm of a few minutes.")
            .translation(key("driftSpeed"))
            .defineInRange("speed", 1.0, 0.0, 10.0);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    /** Relative chances of the snow types in {@link com.pockyl.hoarfrost.weather.SnowType} order. */
    public static int[] typeWeights() {
        return new int[] {FLURRIES_WEIGHT.get(), SNOWFALL_WEIGHT.get(), LARGE_FLAKES_WEIGHT.get(), BLIZZARD_WEIGHT.get()};
    }

    static String key(String name) {
        return Hoarfrost.MOD_ID + ".configuration." + name;
    }
}
