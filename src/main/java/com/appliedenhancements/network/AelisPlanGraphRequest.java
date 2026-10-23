package com.appliedenhancements.network;

import com.appliedenhancements.ae2.AelisPlanGraphMenuBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** The client supplies no recipes, quantities or target inventory. */
public record AelisPlanGraphRequest(int containerId, int request) {
    public static final PacketCodec<FriendlyByteBuf, AelisPlanGraphRequest> STREAM_CODEC = PacketCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.containerId);
                buffer.writeVarInt(packet.request);
            }, buffer -> new AelisPlanGraphRequest(buffer.readVarInt(), buffer.readVarInt()));

    public AelisPlanGraphRequest {
        if (containerId < 0 || request <= 0) throw new IllegalArgumentException("Invalid graph request");
    }

    public static void handle(AelisPlanGraphRequest packet, Player receivingPlayer) {
        if (!(receivingPlayer instanceof ServerPlayer player)) return;
        var menu = player.containerMenu;
        if (menu.containerId == packet.containerId && menu.stillValid(player)
                && menu instanceof AelisPlanGraphMenuBridge bridge) {
            bridge.appliedenhancements$sendPlanGraph(packet.request);
        }
    }
}
