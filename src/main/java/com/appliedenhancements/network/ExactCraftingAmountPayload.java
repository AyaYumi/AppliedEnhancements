package com.appliedenhancements.network;

import com.appliedenhancements.ae2.ExactCraftingMenuBridge;
import java.math.BigInteger;
import net.minecraft.network.FriendlyByteBuf;

/** Decimal request; payload transport bounds and container identity still apply. */
public record ExactCraftingAmountPayload(int containerId, String amount, boolean missing, boolean autoStart)
 {
    public ExactCraftingAmountPayload {
        if (amount == null || !amount.matches("[0-9]+")) throw new IllegalArgumentException("Invalid exact amount");
        new BigInteger(amount);
    }
    public static final PacketCodec<FriendlyByteBuf, ExactCraftingAmountPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.containerId());
                buffer.writeUtf(value.amount(), 1_048_576);
                buffer.writeBoolean(value.missing());
                buffer.writeBoolean(value.autoStart());
            }, buffer -> new ExactCraftingAmountPayload(buffer.readVarInt(), buffer.readUtf(1_048_576),
                    buffer.readBoolean(), buffer.readBoolean()));
    public static void handle(ExactCraftingAmountPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
            var menu = receivingPlayer.containerMenu;
            if (menu.containerId != payload.containerId || !(menu instanceof ExactCraftingMenuBridge bridge)) return;
            var amount = new BigInteger(payload.amount);
            if (!receivingPlayer.level().isClientSide) bridge.appliedenhancements$confirmExact(amount, payload.missing, payload.autoStart);
    }
}
