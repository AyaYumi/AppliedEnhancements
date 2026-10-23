package com.appliedenhancements.network;

import com.appliedenhancements.ae2.ForceCraftingMenuBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Only the player's current, server-owned confirmation plan can be submitted. */
public record ForceCraftingStartPayload(int containerId) {
    public static final PacketCodec<FriendlyByteBuf, ForceCraftingStartPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, payload) -> buffer.writeVarInt(payload.containerId()),
            buffer -> new ForceCraftingStartPayload(buffer.readVarInt()));

    public static void handle(ForceCraftingStartPayload payload, Player receivingPlayer) {
        if (!(receivingPlayer instanceof ServerPlayer player)) return;
        var menu = player.containerMenu;
        if (menu.containerId == payload.containerId() && menu.stillValid(player)
                && menu instanceof ForceCraftingMenuBridge bridge) {
            bridge.appliedenhancements$startForced();
        }
    }
}
