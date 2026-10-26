package com.pockyl.hoarfrost.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiMutableFogRenderParam;
import net.minecraft.util.Mth;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.render.SnowFog;

import java.awt.Color;

/**
 * The haze of falling snow over the distant land of <a href="https://www.curseforge.com/minecraft/mc-mods/distant-horizons">
 * Distant Horizons</a>: its own fog is pulled in to where the snowy haze closes the view and takes the haze's colour, so
 * the far land does not stand out sharp behind a snowstorm. Only loaded when Distant Horizons is installed.
 *
 * <p>Distant Horizons measures its fog in shares of its own LOD render distance (not of the vanilla one, as its config
 * text suggests), and its linear fog is a straight ramp where the vanilla fog is a smoothstep: the start is moved so both
 * agree exactly where the vanilla chunks end.
 */
public final class DistantHorizonsFog {
    private static boolean broken;

    private DistantHorizonsFog() {
    }

    public static void register() {
        SnowFog.setDistantLand(DistantHorizonsFog::lodDistance);
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
                apply(event.value.getFogRenderParam());
            }
        });
    }

    /** How far Distant Horizons draws its land now, in blocks; 0 while it draws none. */
    private static int lodDistance() {
        if (broken) {
            return 0;
        }
        try {
            IDhApiConfig configs = DhApi.Delayed.configs;
            if (configs == null || !Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue())) {
                return 0;
            }
            Integer chunks = configs.graphics().chunkRenderDistance().getValue();
            return chunks == null ? 0 : Math.max(0, chunks) * 16;
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            Hoarfrost.LOGGER.warn("Distant Horizons' render distance could not be read; the snowy haze stays on the vanilla chunks", e);
            return 0;
        }
    }

    private static void apply(DhApiMutableFogRenderParam fog) {
        float k = SnowFog.strength();
        if (k <= 0.0F) {
            return;
        }
        int distance = lodDistance();
        if (distance <= 0) {
            return;
        }
        float near = SnowFog.near();
        float far = SnowFog.far();
        float seam = SnowFog.seam();
        if (seam > near && seam < far) {
            // The vanilla fog at the seam is smoothstep(t); a straight ramp ending at the same far plane matches it there.
            float t = (seam - near) / (far - near);
            float thickness = t * t * (3.0F - 2.0F * t);
            near = Math.max(0.0F, (seam - thickness * far) / (1.0F - thickness));
        }
        float start = near / distance;
        float end = far / distance;
        if (end >= fog.getFarFogEndPercent()) {
            return;
        }
        fog.setFarFogStartPercent(start);
        fog.setFarFogEndPercent(end);
        fog.setFarFogFalloff(EDhApiFogFalloff.LINEAR);
        fog.setFarFogMinThickness(0.0F);
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
