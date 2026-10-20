package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.ForceCraftingMenuBridge;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Only the player's current, server-owned confirmation plan can be submitted. */
public record ForceCraftingStartPayload(int containerId) implements CustomPacketPayload {
    public static final Type<ForceCraftingStartPayload> TYPE =
            new Type<>(AppliedEnhancements.id("force_crafting_start"));
    public static final StreamCodec<ByteBuf, ForceCraftingStartPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(ForceCraftingStartPayload::new, ForceCraftingStartPayload::containerId);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ForceCraftingStartPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var menu = player.containerMenu;
            if (menu.containerId == payload.containerId() && menu.stillValid(player)
                    && menu instanceof ForceCraftingMenuBridge bridge) {
                bridge.appliedenhancements$startForced();
            }
        });
    }
}
