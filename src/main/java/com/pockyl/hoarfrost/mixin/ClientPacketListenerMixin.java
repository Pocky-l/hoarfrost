package com.pockyl.hoarfrost.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.hoarfrost.client.SnowClient;

/**
 * Explosions (TNT, creepers) reach the client only as this packet; Forge's explosion events are
 * server-side. Injected at the tail, which only runs on the main thread.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleExplosion", at = @At("TAIL"))
    private void hoarfrost$explosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        SnowClient.onExplosion(packet);
    }
}
