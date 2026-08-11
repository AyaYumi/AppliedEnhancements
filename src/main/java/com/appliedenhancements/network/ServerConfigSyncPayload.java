package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.github.appliedenhancements.config.AppliedEnhancementsConfig;
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
        boolean progressDisplayEnabled) implements CustomPacketPayload {
    public static final Type<ServerConfigSyncPayload> TYPE =
            new Type<>(AppliedEnhancements.id("server_config"));

    public static final StreamCodec<ByteBuf, ServerConfigSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, ServerConfigSyncPayload::maxCraftingOrderAmount,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::longRangeCraftingEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::progressDisplayEnabled,
                    ServerConfigSyncPayload::new);

    public ServerConfigSyncPayload {
        if (maxCraftingOrderAmount <= 0) {
            throw new IllegalArgumentException("Maximum crafting order amount must be positive");
        }
    }

    public static ServerConfigSyncPayload currentServerValues() {
        return new ServerConfigSyncPayload(
                Config.MAX_CRAFTING_ORDER_AMOUNT.get(),
                AppliedEnhancementsConfig.COMMON.enableLongRangeCrafting.get(),
                AppliedEnhancementsConfig.COMMON.enableProgressDisplay.get());
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, ServerConfigSyncPayload::handle);
    }

    private static void handle(ServerConfigSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerConfigSyncState.accept(
                    payload.maxCraftingOrderAmount,
                    payload.longRangeCraftingEnabled,
                    payload.progressDisplayEnabled);
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
