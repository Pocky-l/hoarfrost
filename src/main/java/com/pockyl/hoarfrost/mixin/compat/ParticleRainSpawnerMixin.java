package com.pockyl.hoarfrost.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.pockyl.hoarfrost.client.SnowClient;
import com.pockyl.hoarfrost.client.compat.ParticleRain;
import com.pockyl.hoarfrost.compat.Climate;

import java.util.ArrayList;

/**
 * Particle Rain's spawner (see {@link ParticleRain}): its falling snow is skipped where this mod draws the snowfall,
 * while its rain, dust and fog spawn as usual. With a season mod it tells snow from rain by the season, like this mod
 * does, so both mods agree where it snows.
 *
 * <p>Applies only when Particle Rain is installed, and every hook is optional: a Particle Rain version without these
 * methods simply keeps all its particles.
 */
@Pseudo
@Mixin(targets = "pigcart.particlerain.ParticleSpawner")
abstract class ParticleRainSpawnerMixin {
    @WrapOperation(method = {"tickSkyFX", "tickSurfaceFX", "tickBlockFX"}, require = 0, at = @At(value = "INVOKE",
            target = "Lpigcart/particlerain/VersionUtil;getPrecipitationAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private static Biome.Precipitation hoarfrost$seasonalPrecipitation(Level level, Holder<Biome> biome, BlockPos pos,
            Operation<Biome.Precipitation> original) {
        return ParticleRain.active() && Climate.seasons() ? Climate.precipitationAt(level, pos) : original.call(level, biome, pos);
    }

    @WrapOperation(method = {"tickSkyFX", "tickSurfaceFX", "tickBlockFX"}, require = 0, at = @At(value = "INVOKE",
            target = "Ljava/util/ArrayList;contains(Ljava/lang/Object;)Z"))
    private static boolean hoarfrost$leaveSnowToHoarfrost(ArrayList<?> presetPrecipitation, Object precipitation,
            Operation<Boolean> original) {
        // The preset's precipitation against the one where the particle would spawn: no falling preset on snow ground.
        if (ParticleRain.yieldsSnow(presetPrecipitation, precipitation == Biome.Precipitation.SNOW && SnowClient.ownsSnowfall())) {
            return false;
        }
        return original.call(presetPrecipitation, precipitation);
    }
}
