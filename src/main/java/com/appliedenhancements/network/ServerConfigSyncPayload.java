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
        boolean infiniteStorageLimitBypassEnabled, boolean bigIntegerEnabled) implements CustomPacketPayload {
    public ServerConfigSyncPayload(long max, boolean longRange, boolean progress, boolean infinite) {
        this(max, longRange, progress, infinite, true);
    }
    public static final Type<ServerConfigSyncPayload> TYPE =
            new Type<>(AppliedEnhancements.id("server_config"));

    public static final StreamCodec<ByteBuf, ServerConfigSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, ServerConfigSyncPayload::maxCraftingOrderAmount,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::longRangeCraftingEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::progressDisplayEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::infiniteStorageLimitBypassEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::bigIntegerEnabled,
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
                Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get(), Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get());
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, ServerConfigSyncPayload::handle);
    }

    private static void handle(ServerConfigSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerConfigSyncState.acceptExact(payload.bigIntegerEnabled);
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
