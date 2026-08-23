package com.appliedenhancements.network;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Handles network packet registration for Applied Enhancements.
 */
public final class NetworkHandler {
    private NetworkHandler() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
                LongCraftingRequestPayload.TYPE,
                LongCraftingRequestPayload.STREAM_CODEC,
                LongCraftingRequestPayload::handle
        );
        registrar.playToServer(
                PatternBatchMovePayload.TYPE,
                PatternBatchMovePayload.STREAM_CODEC,
                PatternBatchMovePayload::handle
        );
    }
}
