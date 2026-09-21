package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.ExactCraftingMenuBridge;
import com.appliedenhancements.api.AelisExactRequest;
import java.math.BigInteger;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Bounded decimal wire format; container identity prevents stale request/reply reuse. */
public record ExactCraftingAmountPayload(int containerId, String amount, boolean missing, boolean autoStart)
        implements CustomPacketPayload {
    public ExactCraftingAmountPayload {
        if (amount == null || !amount.matches("[0-9]{1,256}")) throw new IllegalArgumentException("Invalid exact amount");
        new AelisExactRequest(new BigInteger(amount));
    }
    public static final Type<ExactCraftingAmountPayload> TYPE = new Type<>(AppliedEnhancements.id("exact_crafting_amount"));
    public static final StreamCodec<ByteBuf, ExactCraftingAmountPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, ExactCraftingAmountPayload::containerId,
        ByteBufCodecs.stringUtf8(256), ExactCraftingAmountPayload::amount,
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
