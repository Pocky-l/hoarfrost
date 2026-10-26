package com.pockyl.hoarfrost.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Server to client: the kind of snow falling in the player's level (ordinal, -1 when it does not snow). Sent on join, on
 * change and every few seconds.
 */
public record SnowSyncPayload(int snowKind) {
    public static SnowSyncPayload decode(FriendlyByteBuf buffer) {
        return new SnowSyncPayload(buffer.readVarInt());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(snowKind);
    }
}
