package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.ApiStatus;

/** Server-authoritative feature settings needed by client-side crafting screens. */
@ApiStatus.Internal
public record ServerConfigSyncPayload(
        long maxCraftingOrderAmount,
        boolean longRangeCraftingEnabled,
        boolean progressDisplayEnabled,
        boolean infiniteStorageLimitBypassEnabled) implements CustomPacketPayload {
    public static final Type<ServerConfigSyncPayload> TYPE =
            new Type<>(AppliedEnhancements.id("server_config"));

    public static final StreamCodec<ByteBuf, ServerConfigSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, ServerConfigSyncPayload::maxCraftingOrderAmount,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::longRangeCraftingEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::progressDisplayEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::infiniteStorageLimitBypassEnabled,
                    ServerConfigSyncPayload::new);

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

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, ServerConfigSyncPayload::handle);
    }

    private static void handle(ServerConfigSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerConfigSyncState.accept(
                    payload.maxCraftingOrderAmount,
                    payload.longRangeCraftingEnabled,
                    payload.progressDisplayEnabled,
                    payload.infiniteStorageLimitBypassEnabled);
            ClientCraftingProgressReset.resetIfDisabled(
                    payload.progressDisplayEnabled,
                    context.player().containerMenu);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
