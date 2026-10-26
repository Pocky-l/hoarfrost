package com.pockyl.hoarfrost.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.hoarfrost.client.SnowClient;

/**
 * The mod draws the snowfall, vanilla (or a rain mod) the rain - column by column, so where snowy and rainy land meet
 * each side keeps its own weather.
 *
 * <p>Vanilla draws both in one pass. The snow columns are taken out of it by changing the precipitation stored for each
 * column, not by redirecting the call that computes it: rain mods such as Petrichor redirect that call, and two
 * redirects of one call cannot coexist, while this chains with them. The flakes are drawn at the start of the pass,
 * where shader packs (Iris) already render with their weather program; the dimension's special effects are left
 * alone, since only one mod can own those.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void hoarfrost$drawSnow(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
        SnowClient.renderSnowfall(lightTexture, partialTick, camX, camY, camZ);
    }

    /**
     * Every redraw of a chunk section passes here, also with Sodium (which replaces the body) and from dynamic light mods
     * (LambDynamicLights redraws the sections around its moving lights): the snow on those chunks checks its light.
     */
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), require = 0)
    private void hoarfrost$sectionChanged(int sectionX, int sectionY, int sectionZ, boolean important, CallbackInfo ci) {
        SnowClient.onSectionChanged(sectionX, sectionZ);
    }

    @ModifyVariable(method = "renderSnowAndRain", at = @At("STORE"))
    private Biome.Precipitation hoarfrost$rainOnly(Biome.Precipitation precipitation) {
        return precipitation == Biome.Precipitation.SNOW && SnowClient.ownsSnowfall() ? Biome.Precipitation.NONE : precipitation;
    }
}
