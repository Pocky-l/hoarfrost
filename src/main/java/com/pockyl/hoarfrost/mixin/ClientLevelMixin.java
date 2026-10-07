package com.pockyl.hoarfrost.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.hoarfrost.client.SnowClient;
import com.pockyl.hoarfrost.client.render.SnowSky;

/**
 * Block changes the client sees (the snow on the ground follows what is built and dug; NeoForge has no client-side block
 * change event), and the sky under snow clouds: vanilla darkens it for any precipitation like for rain, a snowy sky is
 * bright and milky. The return values are adjusted (chainable), not replaced.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void hoarfrost$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        SnowClient.onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
    }

    @ModifyReturnValue(method = "getSkyColor", at = @At("RETURN"))
    private Vec3 hoarfrost$snowySky(Vec3 color, Vec3 pos, float partialTick) {
        return SnowSky.skyColor(color, partialTick);
    }

    @ModifyReturnValue(method = "getCloudColor", at = @At("RETURN"))
    private Vec3 hoarfrost$snowyClouds(Vec3 color, float partialTick) {
        return SnowSky.cloudColor(color, partialTick);
    }

    @ModifyReturnValue(method = "getSkyDarken", at = @At("RETURN"))
    private float hoarfrost$snowyDaylight(float darken, float partialTick) {
        return SnowSky.skyDarken((ClientLevel) (Object) this, darken, partialTick);
    }
}
