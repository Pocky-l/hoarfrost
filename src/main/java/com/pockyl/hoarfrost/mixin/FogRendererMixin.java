package com.pockyl.hoarfrost.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.hoarfrost.client.render.SnowFog;

/**
 * Keeps the haze of a snowstorm when another mod turns the vanilla fog off at the end of the fog setup (Distant Horizons
 * does by default). Applied after the default priority, so this runs after their injection.
 */
@Mixin(value = FogRenderer.class, priority = 1500)
abstract class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void hoarfrost$keepSnowHaze(Camera camera, FogRenderer.FogMode mode, float farPlane, boolean thickFog, float partialTick,
            CallbackInfo ci) {
        SnowFog.afterSetup(mode == FogRenderer.FogMode.FOG_TERRAIN);
    }
}
