package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.AelisPlanGraphMenuBridge;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The client supplies no recipes, quantities or target inventory. */
public record AelisPlanGraphRequest(int containerId, int request) implements CustomPacketPayload {
    public static final Type<AelisPlanGraphRequest> TYPE = new Type<>(AppliedEnhancements.id("plan_graph_request"));
    public static final StreamCodec<ByteBuf, AelisPlanGraphRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AelisPlanGraphRequest::containerId,
            ByteBufCodecs.VAR_INT, AelisPlanGraphRequest::request, AelisPlanGraphRequest::new);

    public AelisPlanGraphRequest {
        if (containerId < 0 || request <= 0) throw new IllegalArgumentException("Invalid graph request");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AelisPlanGraphRequest packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var menu = player.containerMenu;
            if (menu.containerId == packet.containerId && menu.stillValid(player)
                    && menu instanceof AelisPlanGraphMenuBridge bridge) {
                bridge.appliedenhancements$sendPlanGraph(packet.request);
            }
        });
    }
}
