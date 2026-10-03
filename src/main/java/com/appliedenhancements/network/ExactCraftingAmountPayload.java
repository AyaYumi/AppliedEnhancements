package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.ExactCraftingMenuBridge;
import java.math.BigInteger;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Decimal request; payload transport bounds and container identity still apply. */
public record ExactCraftingAmountPayload(int containerId, String amount, boolean missing, boolean autoStart)
        implements CustomPacketPayload {
    public ExactCraftingAmountPayload {
        if (amount == null || !amount.matches("[0-9]+")) throw new IllegalArgumentException("Invalid exact amount");
        new BigInteger(amount);
    }
    public static final Type<ExactCraftingAmountPayload> TYPE = new Type<>(AppliedEnhancements.id("exact_crafting_amount"));
    public static final StreamCodec<ByteBuf, ExactCraftingAmountPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, ExactCraftingAmountPayload::containerId,
        ByteBufCodecs.stringUtf8(1_048_576), ExactCraftingAmountPayload::amount,
        ByteBufCodecs.BOOL, ExactCraftingAmountPayload::missing,
        ByteBufCodecs.BOOL, ExactCraftingAmountPayload::autoStart, ExactCraftingAmountPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(ExactCraftingAmountPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var menu = context.player().containerMenu;
            if (menu.containerId != payload.containerId || !(menu instanceof ExactCraftingMenuBridge bridge)) return;
            var amount = new BigInteger(payload.amount);
            if (context.player().level().isClientSide) bridge.appliedenhancements$setInitialExactAmount(amount);
            else bridge.appliedenhancements$confirmExact(amount, payload.missing, payload.autoStart);
        });
    }
}
