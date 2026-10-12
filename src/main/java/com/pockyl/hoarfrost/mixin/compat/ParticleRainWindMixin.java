package com.pockyl.hoarfrost.mixin.compat;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.hoarfrost.client.ClientWeather;
import com.pockyl.hoarfrost.client.SnowClient;
import com.pockyl.hoarfrost.client.compat.ParticleRain;
import com.pockyl.hoarfrost.weather.SnowConditions;

/**
 * Particle Rain's own wind (see {@link ParticleRain}) blows the way this mod's wind does in snowy land, so its dust and
 * fog drift along with the snow instead of across it. Its strength is left to Particle Rain.
 *
 * <p>Applies only when Particle Rain is installed, and the hook is optional. Wind from another mod that drives Particle
 * Rain's particles (WindLink) does not pass through here and is left alone.
 */
@Pseudo
@Mixin(targets = "pigcart.particlerain.ParticleRain")
abstract class ParticleRainWindMixin {
    @Inject(method = "getWind(DDD)Lorg/joml/Vector3f;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void hoarfrost$sameWind(double x, double y, double z, CallbackInfoReturnable<Vector3f> cir) {
        Vector3f wind = cir.getReturnValue();
        if (wind == null || !ParticleRain.active() || !SnowClient.ownsSnowfall() || !SnowClient.snowyAround()) {
            return;
        }
        SnowConditions weather = ClientWeather.conditions();
        float[] aligned = ParticleRain.alignWind(wind.x(), wind.z(), weather.windDirX * weather.windSpeed, weather.windDirZ * weather.windSpeed);
        cir.setReturnValue(new Vector3f(aligned[0], wind.y(), aligned[1]));
    }
}
