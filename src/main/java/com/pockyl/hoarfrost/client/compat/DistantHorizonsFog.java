package com.pockyl.hoarfrost.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiMutableFogRenderParam;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import com.pockyl.hoarfrost.client.render.SnowFog;

import java.awt.Color;

/**
 * The haze of falling snow over the distant land of <a href="https://www.curseforge.com/minecraft/mc-mods/distant-horizons">
 * Distant Horizons</a>: its own fog is pulled in to where the snowy haze closes the view and takes the haze's colour, so
 * the far land does not stand out sharp behind a snowstorm. Only loaded when Distant Horizons is installed.
 */
public final class DistantHorizonsFog {
    private DistantHorizonsFog() {
    }

    public static void register() {
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
                apply(event.value.getFogRenderParam());
            }
        });
    }

    private static void apply(DhApiMutableFogRenderParam fog) {
        float k = SnowFog.strength();
        if (k <= 0.0F) {
            return;
        }
        // Distant Horizons measures its fog in shares of the vanilla render distance.
        float distance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;
        float start = SnowFog.near() / distance;
        float end = SnowFog.far() / distance;
        if (end >= fog.getFarFogEndPercent()) {
            return;
        }
        fog.setFarFogStartPercent(Math.min(fog.getFarFogStartPercent(), start));
        fog.setFarFogEndPercent(end);
        fog.setFarFogFalloff(EDhApiFogFalloff.LINEAR);
        fog.setFarFogMaxThickness(1.0F);
        Color own = fog.getFogColor();
        float mix = Math.min(1.0F, k);
        if (own != null) {
            fog.setFogColor(new Color(
                    Mth.clamp(Mth.lerp(mix, own.getRed() / 255.0F, SnowFog.red()), 0.0F, 1.0F),
                    Mth.clamp(Mth.lerp(mix, own.getGreen() / 255.0F, SnowFog.green()), 0.0F, 1.0F),
                    Mth.clamp(Mth.lerp(mix, own.getBlue() / 255.0F, SnowFog.blue()), 0.0F, 1.0F),
                    own.getAlpha() / 255.0F));
        }
    }
}
