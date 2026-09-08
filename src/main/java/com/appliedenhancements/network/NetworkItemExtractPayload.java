package com.appliedenhancements.network;

import com.appliedenhancements.network.PacketCodec;
import net.minecraft.network.FriendlyByteBuf;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.integration.ae2.NetworkItemExtractionMenuBridge;
import net.minecraft.server.level.ServerPlayer;

/** Server-bound exact-amount extraction request from an ME terminal menu. */
public record NetworkItemExtractPayload(
        int containerId, long serial, long amount) {
    public static final PacketCodec<FriendlyByteBuf, NetworkItemExtractPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.containerId());
                buffer.writeVarLong(value.serial());
                buffer.writeVarLong(value.amount());
            },
            buffer -> new NetworkItemExtractPayload(buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong()));

    public NetworkItemExtractPayload {
        if (containerId < 0 || serial < 0 || amount <= 0) {
            throw new IllegalArgumentException(
                    "Invalid network item extraction request");
        }
    }

    public static void handle(
            NetworkItemExtractPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
            if (!(receivingPlayer instanceof ServerPlayer player)
                    || player.containerMenu.containerId != payload.containerId()
                    || !(player.containerMenu
                            instanceof NetworkItemExtractionMenuBridge bridge)) {
                return;
            }
            bridge.appliedenhancements$extractNetworkItem(
                    payload.serial(), payload.amount());
    }

}
