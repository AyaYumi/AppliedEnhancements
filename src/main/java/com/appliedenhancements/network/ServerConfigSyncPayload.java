package com.appliedenhancements.network;

import com.appliedenhancements.network.PacketCodec;
import net.minecraft.network.FriendlyByteBuf;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import org.jetbrains.annotations.ApiStatus;

/** Server-authoritative feature settings needed by client-side crafting screens. */
@ApiStatus.Internal
public record ServerConfigSyncPayload(
        long maxCraftingOrderAmount,
        boolean longRangeCraftingEnabled,
        boolean progressDisplayEnabled,
        boolean infiniteStorageLimitBypassEnabled) {

    public static final PacketCodec<FriendlyByteBuf, ServerConfigSyncPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, value) -> {
                buffer.writeVarLong(value.maxCraftingOrderAmount());
                buffer.writeBoolean(value.longRangeCraftingEnabled());
                buffer.writeBoolean(value.progressDisplayEnabled());
                buffer.writeBoolean(value.infiniteStorageLimitBypassEnabled());
            },
            buffer -> new ServerConfigSyncPayload(buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean()));

    public ServerConfigSyncPayload {
        if (maxCraftingOrderAmount <= 0) {
            throw new IllegalArgumentException("Maximum crafting order amount must be positive");
        }
    }

    public static ServerConfigSyncPayload currentServerValues() {
        return new ServerConfigSyncPayload(
                Config.MAX_CRAFTING_ORDER_AMOUNT.get(),
                Config.ENABLE_LONG_RANGE_CRAFTING.get(),
                Config.ENABLE_PROGRESS_DISPLAY.get(),
                Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get());
    }


    public static void handle(ServerConfigSyncPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
            ServerConfigSyncState.accept(
                    payload.maxCraftingOrderAmount,
                    payload.longRangeCraftingEnabled,
                    payload.progressDisplayEnabled,
                    payload.infiniteStorageLimitBypassEnabled);
            ClientCraftingProgressReset.resetIfDisabled(
                    payload.progressDisplayEnabled,
                    receivingPlayer.containerMenu);
    }

}
