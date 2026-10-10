package com.pockyl.hoarfrost;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Visuals and sound. Only loaded on the client.
 */
public final class ClientConfig {
    /** Budgets that scale with the hardware. Individual options below multiply on top of these. */
    public enum Quality {
        LOW(3000, 18.0F, 4000, 48.0F, 2500, 4000, 48.0F, 2),
        MEDIUM(6000, 22.0F, 7000, 60.0F, 6000, 8000, 64.0F, 2),
        HIGH(9000, 24.0F, 10000, 72.0F, 9000, 12000, 80.0F, 3),
        ULTRA(13000, 28.0F, 16000, 72.0F, 13000, 18000, 112.0F, 4);

        /** Simulated flakes around the camera and how far out they go. */
        public final int nearFlakes;
        public final float radius;
        /** Flakes in the middle distance and how far out they go. */
        public final int farFlakes;
        public final float farRadius;
        /** Light flakes in the stream past the face (heavy snow and blizzards). */
        public final int streamFlakes;
        /** Grains of powder lifted from the snow. */
        public final int powderGrains;
        /** Snow on the ground is drawn out to this distance. */
        public final float coverRadius;
        /** Milliseconds per tick for looking at new land. */
        public final int surveyMillis;

        Quality(int nearFlakes, float radius, int farFlakes, float farRadius, int streamFlakes, int powderGrains, float coverRadius,
                int surveyMillis) {
            this.nearFlakes = nearFlakes;
            this.radius = radius;
            this.farFlakes = farFlakes;
            this.farRadius = farRadius;
            this.streamFlakes = streamFlakes;
            this.powderGrains = powderGrains;
            this.coverRadius = coverRadius;
            this.surveyMillis = surveyMillis;
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
    public static final ModConfigSpec.BooleanValue DISTANT = BUILDER
            .comment("Snow falling in the middle distance, beyond the simulated flakes.")
            .translation(key("distant"))
            .define("distant", true);
    public static final ModConfigSpec.BooleanValue CLOUDS = BUILDER
            .comment("In heavy snow and blizzards, clouds of snow roll low over the land with the wind and the squalls.")
            .translation(key("clouds"))
            .define("clouds", true);
    public static final ModConfigSpec.BooleanValue STREAM = BUILDER
            .comment("In heavy snow and blizzards, a dense stream of light flakes rushes past the face.")
            .translation(key("stream"))
            .define("stream", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("ground")).push("ground");
    }

    public static final ModConfigSpec.BooleanValue COVER = BUILDER
            .comment("Snow settles on the ground, roofs, crowns and posts: more in the lee and in hollows, less on windswept "
                    + "ridges, a little under crowns and in cave mouths. Melts near lamps and in warm land.")
            .translation(key("cover"))
            .define("cover", true);
    public static final ModConfigSpec.BooleanValue DISTANT_COVER = BUILDER
            .comment("Snow lies on distant land out to the render distance, and on the crowns of trees there (simplified).")
            .translation(key("distantCover"))
            .define("distant", true);
    public static final ModConfigSpec.BooleanValue SNOWY_LEAVES = BUILDER
            .comment("Leaves under snow look frosted.")
            .translation(key("snowyLeaves"))
            .define("snowyLeaves", true);
    public static final ModConfigSpec.BooleanValue FOOTPRINTS = BUILDER
            .comment("Feet press dents into the snow and pack paths.")
            .translation(key("footprints"))
            .define("footprints", true);
    public static final ModConfigSpec.BooleanValue POWDER = BUILDER
            .comment("Gusts, wind charges, explosions and feet lift powder off the snow; it rises in a cloud and settles slowly.")
            .translation(key("powder"))
            .define("powder", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("atmosphere")).push("atmosphere");
    }

    public static final ModConfigSpec.DoubleValue FOG = BUILDER
            .comment("How much falling snow closes in the view distance (a heavy blizzard becomes a whiteout). 0 = vanilla fog.")
            .translation(key("fog"))
            .defineInRange("fog", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.BooleanValue BRIGHT_SKY = BUILDER
            .comment("A snowy sky is soft and bright instead of the dark grey of rain.")
            .translation(key("brightSky"))
            .define("brightSky", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("sound")).push("sound");
    }

    public static final ModConfigSpec.DoubleValue WIND_VOLUME = BUILDER
            .comment("Volume of the wind of snowy weather. Light snow is always silent.")
            .translation(key("windVolume"))
            .defineInRange("windVolume", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue HUSH = BUILDER
            .comment("How much falling snow hushes the sounds of the world outdoors. 0 = off.")
            .translation(key("hush"))
            .defineInRange("hush", 1.0, 0.0, 1.5);
    public static final ModConfigSpec.BooleanValue SNOW_STEPS = BUILDER
            .comment("Footsteps crunch in snow: fresh powder and trodden snow sound different.")
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
