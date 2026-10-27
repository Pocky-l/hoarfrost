package com.pockyl.hoarfrost.client.compat;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.ClientWeather;
import com.pockyl.hoarfrost.weather.SnowConditions;

/**
 * Hands the snow to shader packs once per frame, before the world is drawn ({@code hoarfrost:shader_data}, see
 * {@link ShaderData}): how hard it snows, blizzards, visibility and wind.
 * <pre>
 * 3  snow 0..1, snow streaming in the wind 0..1 (blizzards), visibility (blocks), snow density
 * 4  accumulation, gust factor of the snow (1 = steady), -, -
 * </pre>
 */
@EventBusSubscriber(modid = Hoarfrost.MOD_ID, value = Dist.CLIENT)
public final class PackWeather {
    private static final ShaderData DATA = new ShaderData(Hoarfrost.MOD_ID, 5);

    private PackWeather() {
    }

    @SubscribeEvent
    static void onFrameStart(RenderFrameEvent.Pre event) {
        if (!DATA.begin(ShaderPacks.inUse())) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        SnowConditions conditions = ClientWeather.conditions();
        DATA.wind(conditions.windDirX * conditions.windSpeed, conditions.windDirZ * conditions.windSpeed, partialTick);
        DATA.set(3, ClientWeather.snow(), conditions.stream, conditions.visibility, conditions.density);
        DATA.set(4, conditions.accumulation, ClientWeather.gust(), 0.0F, 0.0F);
        DATA.upload();
    }
}
