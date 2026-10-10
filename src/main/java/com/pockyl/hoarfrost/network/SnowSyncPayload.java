package com.pockyl.hoarfrost.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.pockyl.hoarfrost.Hoarfrost;

/**
 * Server to client: the kind of snow falling in the player's level (ordinal, -1 when it does not snow). Sent on join, on
 * change and every few seconds.
 */
public record SnowSyncPayload(int snowKind) implements CustomPacketPayload {
    public static final Type<SnowSyncPayload> TYPE = new Type<>(Hoarfrost.id("snow_sync"));

    public static final StreamCodec<ByteBuf, SnowSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SnowSyncPayload::snowKind,
            SnowSyncPayload::new);

    @Override
    public Type<SnowSyncPayload> type() {
        return TYPE;
    }
}
