package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.appliedenhancements.CraftingOrderMode;
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
        int craftingOrderModeId,
        boolean progressDisplayEnabled,
        boolean infiniteStorageLimitBypassEnabled, boolean bigIntegerEnabled) implements CustomPacketPayload {
    public ServerConfigSyncPayload(CraftingOrderMode mode, boolean progress, boolean infinite) {
        this(mode.id(), progress, infinite, mode.supportsBigInteger());
    }

    public ServerConfigSyncPayload(CraftingOrderMode mode, boolean progress, boolean infinite,
            boolean bigIntegerEnabled) {
        this(mode.id(), progress, infinite, bigIntegerEnabled);
    }
    public static final Type<ServerConfigSyncPayload> TYPE =
            new Type<>(AppliedEnhancements.id("server_config"));

    public static final StreamCodec<ByteBuf, ServerConfigSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ServerConfigSyncPayload::craftingOrderModeId,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::progressDisplayEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::infiniteStorageLimitBypassEnabled,
                    ByteBufCodecs.BOOL, ServerConfigSyncPayload::bigIntegerEnabled,
                    ServerConfigSyncPayload::new);

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

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, ServerConfigSyncPayload::handle);
    }

    private static void handle(ServerConfigSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerConfigSyncState.acceptExact(payload.bigIntegerEnabled);
            ServerConfigSyncState.accept(
                    payload.craftingOrderMode(),
                    payload.progressDisplayEnabled,
                    payload.infiniteStorageLimitBypassEnabled,
                    payload.bigIntegerEnabled);
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
