package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.LongCraftingAmountMenuBridge;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Network payload for long-range crafting requests.
 * Allows crafting orders to exceed Integer.MAX_VALUE.
 */
public record LongCraftingRequestPayload(long amount, boolean craftMissingAmount, boolean autoStart)
        implements CustomPacketPayload {

    public LongCraftingRequestPayload {
        if (amount <= 0) {
            throw new IllegalArgumentException("Crafting amount must be positive");
        }
    }

    public static final CustomPacketPayload.Type<LongCraftingRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    AppliedEnhancements.MODID, "long_crafting_request"));

    public static final StreamCodec<ByteBuf, LongCraftingRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, LongCraftingRequestPayload::amount,
                    ByteBufCodecs.BOOL, LongCraftingRequestPayload::craftMissingAmount,
                    ByteBufCodecs.BOOL, LongCraftingRequestPayload::autoStart,
                    LongCraftingRequestPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LongCraftingRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof LongCraftingAmountMenuBridge bridge) {
                bridge.appliedenhancements$confirmLong(
                        payload.amount,
                        payload.craftMissingAmount,
                        payload.autoStart);
            }
        });
    }
}
