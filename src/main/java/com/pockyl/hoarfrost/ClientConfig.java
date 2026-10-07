package com.pockyl.hoarfrost;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Visuals and sound. Only loaded on the client.
 */
public final class ClientConfig {
    /** Budgets that scale with the hardware. Individual options below multiply on top of these. */
    public enum Quality {
        // maxFlakes, flakeRadius, coverRadius, coverChunksPerTick, farFlakes
        LOW(3000, 14.0F, 48.0F, 2, 2000),
        MEDIUM(6000, 18.0F, 64.0F, 3, 4000),
        HIGH(12000, 22.0F, 96.0F, 3, 8000),
        ULTRA(20000, 28.0F, 128.0F, 4, 14000);

        public final int maxFlakes;
        public final float flakeRadius;
        public final float coverRadius;
        public final int coverChunksPerTick;
        /** Flakes falling in the middle distance beyond the simulated ones. */
        public final int farFlakes;

        Quality(int maxFlakes, float flakeRadius, float coverRadius, int coverChunksPerTick, int farFlakes) {
            this.maxFlakes = maxFlakes;
            this.flakeRadius = flakeRadius;
            this.coverRadius = coverRadius;
            this.coverChunksPerTick = coverChunksPerTick;
            this.farFlakes = farFlakes;
        }
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<Quality> QUALITY = BUILDER
            .comment("Quality preset: how many flakes, how far they and the snow on the ground are simulated and drawn. Lower it "
                    + "on weak computers.")
            .translation(key("quality"))
            .defineEnum("quality", Quality.HIGH);

    static {
        BUILDER.translation(key("snowfall")).push("snowfall");
    }

    public static final ModConfigSpec.BooleanValue SNOWFALL = BUILDER
            .comment("Replace the vanilla snowfall with simulated flakes. Off: vanilla snow is drawn.")
            .translation(key("snowfallEnabled"))
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue DENSITY = BUILDER
            .comment("Multiplier for the number of flakes.")
            .translation(key("density"))
            .defineInRange("density", 1.0, 0.1, 3.0);
    public static final ModConfigSpec.BooleanValue CURTAINS = BUILDER
            .comment("Snow falling in the middle distance, beyond the simulated flakes.")
            .translation(key("curtains"))
            .define("curtains", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("ground")).push("ground");
    }

    public static final ModConfigSpec.BooleanValue COVER = BUILDER
            .comment("Snow settles on the ground, roofs, crowns, fences and stairs, grows during a snowfall and melts near lamps.")
            .translation(key("cover"))
            .define("cover", true);
    public static final ModConfigSpec.BooleanValue DRIFTING = BUILDER
            .comment("Strong wind blows the snow on the ground along and piles it up in drifts behind obstacles.")
            .translation(key("drifting"))
            .define("drifting", true);
    public static final ModConfigSpec.BooleanValue FOOTPRINTS = BUILDER
            .comment("Feet leave prints in the snow and kick up powder; landings and explosions blow it away.")
            .translation(key("footprints"))
            .define("footprints", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("atmosphere")).push("atmosphere");
    }

    public static final ModConfigSpec.DoubleValue FOG = BUILDER
            .comment("How much snow closes in the view distance (a blizzard becomes a whiteout). 0 = vanilla fog.")
            .translation(key("fog"))
            .defineInRange("fog", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.BooleanValue BRIGHT_SKY = BUILDER
            .comment("A snowy sky is milky and bright instead of the dark grey of rain.")
            .translation(key("brightSky"))
            .define("brightSky", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("sound")).push("sound");
    }

    public static final ModConfigSpec.DoubleValue HUSH = BUILDER
            .comment("How much falling snow hushes the sounds of the world outdoors. 0 = off.")
            .translation(key("hush"))
            .defineInRange("hush", 1.0, 0.0, 1.5);
    public static final ModConfigSpec.DoubleValue WIND_VOLUME = BUILDER
            .comment("Volume of the wind of snowstorms.")
            .translation(key("windVolume"))
            .defineInRange("windVolume", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.BooleanValue SNOW_STEPS = BUILDER
            .comment("Footsteps crunch in snow: fresh powder, packed snow and drifts sound different.")
            .translation(key("snowSteps"))
            .define("snowSteps", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    public static Quality quality() {
        return QUALITY.get();
    }

    private static String key(String name) {
        return Config.key(name);
    }
}
