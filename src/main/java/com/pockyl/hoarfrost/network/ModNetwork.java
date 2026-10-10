package com.pockyl.hoarfrost.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.pockyl.hoarfrost.client.ClientWeather;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "2";

    private ModNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Optional both ways: the client works on servers without the mod and vanilla clients can join a modded server.
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        // The lambda only resolves the client class when a packet arrives on a client.
        registrar.playToClient(SnowSyncPayload.TYPE, SnowSyncPayload.STREAM_CODEC,
                (payload, context) -> ClientWeather.onServerSync(payload.snowKind()));
    }
}
