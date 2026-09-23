package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.ExactCraftingMenuBridge;
import com.appliedenhancements.api.AelisExactRequest;
import java.math.BigInteger;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded decimal wire format; container identity prevents stale request/reply reuse. */
public record ExactCraftingAmountPayload(int containerId, String amount, boolean missing, boolean autoStart)
 {
    public ExactCraftingAmountPayload {
        if (amount == null || !amount.matches("[0-9]{1,256}")) throw new IllegalArgumentException("Invalid exact amount");
        new AelisExactRequest(new BigInteger(amount));
    }
    public static final PacketCodec<FriendlyByteBuf, ExactCraftingAmountPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.containerId());
                buffer.writeUtf(value.amount(), 256);
                buffer.writeBoolean(value.missing());
                buffer.writeBoolean(value.autoStart());
            }, buffer -> new ExactCraftingAmountPayload(buffer.readVarInt(), buffer.readUtf(256),
                    buffer.readBoolean(), buffer.readBoolean()));
    public static void handle(ExactCraftingAmountPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
            var menu = receivingPlayer.containerMenu;
            if (menu.containerId != payload.containerId || !(menu instanceof ExactCraftingMenuBridge bridge)) return;
            var amount = new BigInteger(payload.amount);
            if (receivingPlayer.level().isClientSide) bridge.appliedenhancements$setInitialExactAmount(amount);
            else bridge.appliedenhancements$confirmExact(amount, payload.missing, payload.autoStart);
    }
}
