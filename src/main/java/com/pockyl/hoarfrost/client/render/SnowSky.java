package com.pockyl.hoarfrost.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.client.ClientWeather;

/**
 * The look of a snowy sky and air. Vanilla treats every precipitation like rain, and a thunderstorm like a darker rain:
 * the sky, the clouds and the distance turn a dirty dark grey. Under snow clouds it is different - the sky is milky
 * and bright, a snowstorm by day a soft blue-grey, the light is soft but not dim (fresh snow throws it
 * back, even moonlight at night), and the haze of falling snow is white-blue.
 *
 * <p>Everything here fades with the share of snowy land around the player, so rain elsewhere keeps its own look, and the
 * haze with how open the sky above the camera is, so it never fills a room or a cave.
 */
public final class SnowSky {
    private SnowSky() {
    }

    private static boolean enabled() {
        return ClientConfig.BRIGHT_SKY.get();
    }

    private static float luma(double r, double g, double b) {
        return (float) (r * 0.3 + g * 0.59 + b * 0.11);
    }

    /** How much of vanilla's darkening of a colour by rain and thunder is taken back: by the share of snowy land. */
    private static float restore(ClientLevel level, float partialTick, float rainLoss, float thunderLoss) {
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);
        float kept = (1.0F - rainLoss * rain) * (1.0F - thunderLoss * thunder);
        float presence = ClientWeather.presence();
        return 1.0F / Mth.lerp(presence, 1.0F, Math.max(0.2F, kept));
    }

    /**
     * How overcast the snowy weather is, 0..1: light snow and a steady snowfall fall from a high, thin cloud and leave
     * the sky its colour; heavy snow and blizzards hide it.
     */
    private static float overcast() {
        float k = Mth.clamp((140.0F - ClientWeather.conditions().visibility) / 120.0F, 0.0F, 1.0F);
        return k * k;
    }

    /**
     * Undoes vanilla's greying of a colour by rain or thunder: {@code c' = c m + luma(c) f (1 - m)} with
     * {@code m = 1 - level x keep}.
     */
    private static Vec3 ungrey(Vec3 c, float level, float keep, float f) {
        float m = 1.0F - level * keep;
        if (level <= 0.0F || m <= 0.0F) {
            return c;
        }
        float scale = m + f * (1.0F - m);
        float l = luma(c.x, c.y, c.z) / scale * f * (1.0F - m);
        return new Vec3(Mth.clamp((c.x - l) / m, 0.0, 1.0), Mth.clamp((c.y - l) / m, 0.0, 1.0), Mth.clamp((c.z - l) / m, 0.0, 1.0));
    }

    /**
     * The sky colour: in snowy land vanilla's rainy grey is taken back, so light snow falls from a sky of its own colour;
     * the heavier the snow, the more it turns an overcast, slightly blue grey of the brightness the time of day gives.
     */
    public static Vec3 skyColor(Vec3 color, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        float presence = ClientWeather.presence();
        if (!enabled() || level == null || presence <= 0.0F) {
            return color;
        }
        // Vanilla mixes the sky towards 0.6 x its luma by 0.75 x rain, then towards 0.2 x its luma by 0.75 x thunder.
        Vec3 clear = ungrey(ungrey(color, level.getThunderLevel(partialTick), 0.75F, 0.2F), level.getRainLevel(partialTick), 0.75F, 0.6F);
        Vec3 base = color.lerp(clear, presence);
        float k = ClientWeather.snow() * overcast() * 0.6F;
        if (k <= 0.0F) {
            return base;
        }
        float l = Math.min(1.0F, luma(clear.x, clear.y, clear.z) * 0.95F);
        return base.lerp(new Vec3(l * 0.86F, l * 0.93F, l * 1.04F), k);
    }

    /** Snow clouds: white in light snow, pale grey in heavy snow - not the dark grey of rain clouds. */
    public static Vec3 cloudColor(Vec3 color, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        float presence = ClientWeather.presence();
        if (!enabled() || level == null || presence <= 0.0F) {
            return color;
        }
        // Vanilla's clouds without rain: white in the light of the time of day.
        float day = Mth.clamp(Mth.cos(level.getTimeOfDay(partialTick) * Mth.TWO_PI) * 2.0F + 0.5F, 0.0F, 1.0F);
        Vec3 clear = new Vec3(day * 0.9F + 0.1F, day * 0.9F + 0.1F, day * 0.85F + 0.15F);
        Vec3 base = color.lerp(clear, presence);
        float k = ClientWeather.snow() * overcast() * 0.7F;
        if (k <= 0.0F) {
            return base;
        }
        float l = Math.min(1.0F, luma(clear.x, clear.y, clear.z) * 0.85F);
        return base.lerp(new Vec3(l * 0.95F, l * 0.97F, l), k);
    }

    /**
     * Daylight: most of the darkening vanilla gives rain and thunder is taken back in snowy land - snow light is soft,
     * not dim - and at night snowy land stays a little brighter, the snow throwing back the light of the moon.
     */
    public static float skyDarken(ClientLevel level, float darken, float partialTick) {
        float presence = ClientWeather.presence();
        if (!enabled() || presence <= 0.0F) {
            return darken;
        }
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);
        float dimming = (1.0F - rain * 5.0F / 16.0F) * (1.0F - thunder * 5.0F / 16.0F);
        float result = darken;
        if (dimming > 0.01F && dimming < 0.999F) {
            float clear = (darken - 0.2F) / 0.8F / dimming;
            float snowy = Mth.clamp(clear * (1.0F - rain * 0.1F * overcast()), 0.0F, 1.0F) * 0.8F + 0.2F;
            result = Mth.lerp(presence * (1.0F - 0.25F * overcast()), darken, Math.max(darken, snowy));
        }
        return Math.max(result, 0.2F + 0.06F * presence);
    }

    /**
     * Fog colour: the haze of falling snow is a cool blue-grey of the brightness of the sky - not vanilla's dark grey,
     * and not a white wall either: the land shows through it in layers.
     */
    public static float[] fogColor(float r, float g, float b, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || ClientWeather.presence() <= 0.0F) {
            return null;
        }
        // Vanilla multiplies the fog by 1 - 0.5 x rain (blue 0.4) and 1 - 0.5 x thunder.
        float back = enabled() ? restore(level, partialTick, 0.5F, 0.5F) : 1.0F;
        r = Math.min(1.0F, r * back);
        g = Math.min(1.0F, g * back);
        b = Math.min(1.0F, b * back);
        float k = ClientWeather.snow() * ClientWeather.skyView(partialTick) * (float) Math.min(1.0, ClientConfig.FOG.get());
        if (k <= 0.0F) {
            return new float[] {r, g, b};
        }
        float l = Math.min(1.0F, luma(r, g, b) * 0.95F);
        // Light snow leaves the colour of the distance nearly as it is, like the sky.
        float mix = k * 0.45F * (0.25F + 0.75F * overcast());
        return new float[] {
                Mth.lerp(mix, r, l * 0.84F),
                Mth.lerp(mix, g, l * 0.92F),
                Mth.lerp(mix, b, Math.min(1.0F, l * 1.05F))};
    }
}
