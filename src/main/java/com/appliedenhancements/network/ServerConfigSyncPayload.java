package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.appliedenhancements.CraftingOrderMode;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.ApiStatus;

/** Server-authoritative feature settings needed by client-side crafting screens. */
@ApiStatus.Internal
public record ServerConfigSyncPayload(
        int craftingOrderModeId,
        boolean progressDisplayEnabled,
        boolean infiniteStorageLimitBypassEnabled, boolean bigIntegerEnabled) {
    public ServerConfigSyncPayload(CraftingOrderMode mode, boolean progress, boolean infinite) {
        this(mode.id(), progress, infinite, mode.supportsBigInteger());
    }

    public ServerConfigSyncPayload(CraftingOrderMode mode, boolean progress, boolean infinite,
            boolean bigIntegerEnabled) {
        this(mode.id(), progress, infinite, bigIntegerEnabled);
    }
    public static final PacketCodec<FriendlyByteBuf, ServerConfigSyncPayload> STREAM_CODEC = PacketCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.craftingOrderModeId());
                buffer.writeBoolean(value.progressDisplayEnabled());
                buffer.writeBoolean(value.infiniteStorageLimitBypassEnabled());
                buffer.writeBoolean(value.bigIntegerEnabled());
            }, buffer -> new ServerConfigSyncPayload(buffer.readVarInt(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean()));

    public ServerConfigSyncPayload {
        CraftingOrderMode.fromId(craftingOrderModeId);
    }

    public CraftingOrderMode craftingOrderMode() {
        return CraftingOrderMode.fromId(craftingOrderModeId);
    }

    public static ServerConfigSyncPayload currentServerValues() {
        return new ServerConfigSyncPayload(
                Config.MAX_CRAFTING_ORDER_AMOUNT.get(),
                Config.ENABLE_PROGRESS_DISPLAY.get(),
                Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get(),
                Config.MAX_CRAFTING_ORDER_AMOUNT.get().supportsBigInteger()
                        && Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get());
    }

    public static void handle(ServerConfigSyncPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
            ServerConfigSyncState.acceptExact(payload.bigIntegerEnabled);
            ServerConfigSyncState.accept(
                    payload.craftingOrderMode(),
                    payload.progressDisplayEnabled,
                    payload.infiniteStorageLimitBypassEnabled,
                    payload.bigIntegerEnabled);
            ClientCraftingProgressReset.resetIfDisabled(
                    payload.progressDisplayEnabled,
                    receivingPlayer.containerMenu);
    }
}
