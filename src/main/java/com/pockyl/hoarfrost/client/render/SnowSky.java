package com.pockyl.hoarfrost.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.client.ClientSnow;

/**
 * The look of a snowy sky and air. Vanilla treats every precipitation like rain: a dark grey sky, dimmer daylight. Under
 * snow clouds the sky is milky and bright, the light is soft but not dim (fresh snow throws it back), and the air
 * fills with a white-blue haze that closes in to a whiteout in a blizzard.
 */
public final class SnowSky {
    private SnowSky() {
    }

    private static float amount() {
        if (!ClientConfig.BRIGHT_SKY.get()) {
            return 0.0F;
        }
        return ClientSnow.snow();
    }

    private static float luma(Vec3 color) {
        return (float) (color.x * 0.3 + color.y * 0.59 + color.z * 0.11);
    }

    /** The sky colour: towards a light, slightly blue milky white of the same brightness the time of day gives. */
    public static Vec3 skyColor(Vec3 color, float partialTick) {
        float k = amount() * 0.65F;
        if (k <= 0.0F) {
            return color;
        }
        float l = Math.min(1.0F, luma(color) * 1.45F + 0.02F);
        return color.lerp(new Vec3(l * 0.93F, l * 0.96F, l), k);
    }

    /** Snow clouds are pale grey, not the dark grey of rain clouds. */
    public static Vec3 cloudColor(Vec3 color, float partialTick) {
        float k = amount() * 0.6F;
        if (k <= 0.0F) {
            return color;
        }
        float l = Math.min(1.0F, luma(color) * 1.35F);
        return color.lerp(new Vec3(l * 0.96F, l * 0.97F, l), k);
    }

    /** Daylight: much of the darkening vanilla gives precipitation is taken back - snow light is soft, not dim. */
    public static float skyDarken(ClientLevel level, float darken, float partialTick) {
        float k = amount();
        if (k <= 0.0F) {
            return darken;
        }
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);
        float dimming = (1.0F - rain * 5.0F / 16.0F) * (1.0F - thunder * 5.0F / 16.0F);
        if (dimming <= 0.01F) {
            return darken;
        }
        float clear = (darken - 0.2F) / 0.8F / dimming;
        float snowy = clear * (1.0F - rain * 0.1F);
        float target = Mth.clamp(snowy, 0.0F, 1.0F) * 0.8F + 0.2F;
        // The rain level is already in the darkening; how much of it is snow is the share of snowy land around.
        return Mth.lerp(ClientSnow.presence() * 0.7F, darken, Math.max(darken, target));
    }

    /**
     * Fog colour: the haze of falling snow is white-blue and lighter than the air, by day and night alike (at night it
     * glows faintly).
     */
    public static float[] fogColor(float r, float g, float b, float partialTick) {
        float k = ClientSnow.snow() * ClientSnow.skyView(partialTick) * (float) Math.min(1.0, ClientConfig.FOG.get());
        if (k <= 0.0F) {
            return null;
        }
        float l = r * 0.3F + g * 0.59F + b * 0.11F;
        float bright = Math.min(1.0F, l * 1.4F + 0.03F);
        float mix = k * (0.45F + 0.4F * Mth.clamp((260.0F - ClientSnow.visibility) / 220.0F, 0.0F, 1.0F));
        return new float[] {
                Mth.lerp(mix, r, bright * 0.91F),
                Mth.lerp(mix, g, bright * 0.95F),
                Mth.lerp(mix, b, bright)};
    }
}
