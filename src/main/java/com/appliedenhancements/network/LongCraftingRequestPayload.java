package com.appliedenhancements.network;

import com.appliedenhancements.network.PacketCodec;
import net.minecraft.network.FriendlyByteBuf;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.LongCraftingAmountMenuBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Network payload for long-range crafting requests.
 * Allows crafting orders to exceed Integer.MAX_VALUE.
 */
public record LongCraftingRequestPayload(long amount, boolean craftMissingAmount, boolean autoStart) {

    public LongCraftingRequestPayload {
        if (amount <= 0) {
            throw new IllegalArgumentException("Crafting amount must be positive");
        }
    }


    public static final PacketCodec<FriendlyByteBuf, LongCraftingRequestPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, value) -> {
                buffer.writeVarLong(value.amount());
                buffer.writeBoolean(value.craftMissingAmount());
                buffer.writeBoolean(value.autoStart());
            },
            buffer -> new LongCraftingRequestPayload(buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean()));


    public static void handle(LongCraftingRequestPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
            if (receivingPlayer instanceof ServerPlayer player
                    && player.containerMenu instanceof LongCraftingAmountMenuBridge bridge) {
                bridge.appliedenhancements$confirmLong(
                        payload.amount,
                        payload.craftMissingAmount,
                        payload.autoStart);
            }
    }
}
