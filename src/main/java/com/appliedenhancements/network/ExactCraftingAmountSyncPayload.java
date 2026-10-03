package com.appliedenhancements.network;

import com.appliedenhancements.ae2.ExactCraftingMenuBridge;
import java.math.BigInteger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

/** Clientbound restoration of the exact value when returning to the amount screen. */
public record ExactCraftingAmountSyncPayload(int containerId, String amount) {
    public ExactCraftingAmountSyncPayload {
        if (amount == null || !amount.matches("[0-9]+")) throw new IllegalArgumentException("Invalid exact amount");
    }

    public static final PacketCodec<FriendlyByteBuf, ExactCraftingAmountSyncPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.containerId());
                buffer.writeUtf(value.amount(), 1_048_576);
            }, buffer -> new ExactCraftingAmountSyncPayload(buffer.readVarInt(), buffer.readUtf(1_048_576)));

    public static void handle(ExactCraftingAmountSyncPayload payload, Player player) {
        var menu = player.containerMenu;
        if (player.level().isClientSide && menu.containerId == payload.containerId
                && menu instanceof ExactCraftingMenuBridge bridge) {
            bridge.appliedenhancements$setInitialExactAmount(new BigInteger(payload.amount));
        }
    }
}
