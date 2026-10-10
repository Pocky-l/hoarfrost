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

    /** The sky colour: an overcast, slightly blue grey of the brightness the time of day gives. */
    public static Vec3 skyColor(Vec3 color, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        float k = ClientWeather.snow() * 0.6F;
        if (!enabled() || level == null || k <= 0.0F) {
            return color;
        }
        // Vanilla greys the sky by 0.3 x rain and 0.6 x thunder of its brightness.
        float l = Math.min(1.0F, luma(color.x, color.y, color.z) * restore(level, partialTick, 0.3F, 0.6F) * 0.95F);
        return color.lerp(new Vec3(l * 0.86F, l * 0.93F, l * 1.04F), k);
    }

    /** Snow clouds are pale grey, not the dark grey of rain clouds. */
    public static Vec3 cloudColor(Vec3 color, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        float k = ClientWeather.snow() * 0.7F;
        if (!enabled() || level == null || k <= 0.0F) {
            return color;
        }
        float l = Math.min(1.0F, luma(color.x, color.y, color.z) * restore(level, partialTick, 0.38F, 0.76F) * 1.05F);
        return color.lerp(new Vec3(l * 0.95F, l * 0.97F, l), k);
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
            float snowy = Mth.clamp(clear * (1.0F - rain * 0.1F), 0.0F, 1.0F) * 0.8F + 0.2F;
            result = Mth.lerp(presence * 0.75F, darken, Math.max(darken, snowy));
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
        float mix = k * 0.45F;
        return new float[] {
                Mth.lerp(mix, r, l * 0.84F),
                Mth.lerp(mix, g, l * 0.92F),
                Mth.lerp(mix, b, Math.min(1.0F, l * 1.05F))};
    }
}
