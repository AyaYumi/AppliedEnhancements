package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.integration.ae2.NetworkItemExtractionMenuBridge;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-bound exact-amount extraction request from an ME terminal menu. */
public record NetworkItemExtractPayload(
        int containerId, long serial, long amount) implements CustomPacketPayload {
    public static final Type<NetworkItemExtractPayload> TYPE =
            new Type<>(AppliedEnhancements.id("network_item_extract"));
    public static final StreamCodec<ByteBuf, NetworkItemExtractPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, NetworkItemExtractPayload::containerId,
                    ByteBufCodecs.VAR_LONG, NetworkItemExtractPayload::serial,
                    ByteBufCodecs.VAR_LONG, NetworkItemExtractPayload::amount,
                    NetworkItemExtractPayload::new);

    public NetworkItemExtractPayload {
        if (containerId < 0 || serial < 0 || amount <= 0) {
            throw new IllegalArgumentException(
                    "Invalid network item extraction request");
        }
    }

    public static void handle(
            NetworkItemExtractPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || player.containerMenu.containerId != payload.containerId()
                    || !(player.containerMenu
                            instanceof NetworkItemExtractionMenuBridge bridge)) {
                return;
            }
            bridge.appliedenhancements$extractNetworkItem(
                    payload.serial(), payload.amount());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
