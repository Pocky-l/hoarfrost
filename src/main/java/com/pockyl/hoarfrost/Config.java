package com.pockyl.hoarfrost;

import net.minecraftforge.common.ForgeConfigSpec;

import com.pockyl.hoarfrost.weather.SnowKind;

/**
 * The weather and what the server does with the world (server config). A client on a server without the mod has no
 * server config: it follows the defaults for the course of the snowfall.
 */
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    static {
        BUILDER.translation(key("weather")).push("weather");
    }

    private static final ForgeConfigSpec.IntValue[] WEIGHTS = {
            weight(SnowKind.LIGHT_SNOW, "Light snow: a few slow crystals in still air, silent.", 4),
            weight(SnowKind.SNOW, "Snow: a steady snowfall with a soft breeze.", 5),
            weight(SnowKind.HEAVY_SNOW, "Heavy snow: thick snowfall, the land fading into white.", 3),
            weight(SnowKind.HEAVY_LARGE_FLAKES, "Heavy snow with large flakes tumbling down through calm air.", 2),
            weight(SnowKind.LIGHT_BLIZZARD, "Light blizzard: fine snow driven by a fresh wind.", 2),
            weight(SnowKind.BLIZZARD, "Blizzard: a howling snowstorm.", 1),
            weight(SnowKind.HEAVY_BLIZZARD, "Heavy blizzard: a whiteout.", 0)};
    public static final ForgeConfigSpec.BooleanValue THUNDER_BLIZZARD = BUILDER
            .comment("A thunderstorm in snowy land is one of the blizzards.")
            .translation(key("thunderBlizzard"))
            .define("thunderBlizzard", true);
    public static final ForgeConfigSpec.BooleanValue SEASONS = BUILDER
            .comment("With Serene Seasons: snow falls and lies where the season makes it cold (temperate land in winter, none in "
                    + "a warm summer), and deep winter brings heavier snow and blizzards.")
            .translation(key("seasons"))
            .define("followSeasons", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("drifts")).push("drifts");
    }

    public static final ForgeConfigSpec.BooleanValue DRIFTS = BUILDER
            .comment("While snow falls with wind, snow layers pile up in the lee of walls, trees and hills. Changes the world "
                    + "(adds snow layer blocks, never removes anything); only under the open sky.")
            .translation(key("driftsEnabled"))
            .define("enabled", true);
    public static final ForgeConfigSpec.IntValue DRIFT_MAX_LAYERS = BUILDER
            .comment("Highest drift in snow layers (8 = a full block). 4 or less can always be walked over.")
            .translation(key("driftMaxLayers"))
            .defineInRange("maxLayers", 4, 1, 8);
    public static final ForgeConfigSpec.DoubleValue DRIFT_SPEED = BUILDER
            .comment("How fast drifts grow. 1 = a drift of four layers takes a blizzard of a few minutes.")
            .translation(key("driftSpeed"))
            .defineInRange("speed", 1.0, 0.0, 10.0);

    static {
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    private static ForgeConfigSpec.IntValue weight(SnowKind kind, String comment, int value) {
        String name = camel(kind.id()) + "Weight";
        return BUILDER.comment(comment + " Relative chance when it starts to snow and every five minutes of a snowfall.")
                .translation(key(name))
                .defineInRange(name, value, 0, 100);
    }

    private static String camel(String id) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : id.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /** Relative chances of the kinds in {@link SnowKind} order. */
    public static int[] kindWeights() {
        int[] weights = new int[WEIGHTS.length];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = value(WEIGHTS[i]);
        }
        return weights;
    }

    public static boolean thunderBlizzard() {
        return value(THUNDER_BLIZZARD);
    }

    public static boolean followSeasons() {
        return value(SEASONS);
    }

    /** The value, or its default while the server config is not loaded (on a server without the mod). */
    public static <T> T value(ForgeConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    static String key(String name) {
        return Hoarfrost.MOD_ID + ".configuration." + name;
    }
}
