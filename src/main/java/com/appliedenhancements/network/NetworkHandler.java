package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.github.appliedenhancements.network.CraftingCalculationPathPayload;
import com.github.appliedenhancements.network.CraftingCalculationProgressPayload;
import java.util.function.BiConsumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {
    private static final String PROTOCOL = "1.0.6-forge-1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            AppliedEnhancements.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private NetworkHandler() {}

    public static void register() {
        register(0, LongCraftingRequestPayload.class, LongCraftingRequestPayload.STREAM_CODEC,
                LongCraftingRequestPayload::handle, NetworkDirection.PLAY_TO_SERVER);
        register(1, PatternBatchMovePayload.class, PatternBatchMovePayload.STREAM_CODEC,
                PatternBatchMovePayload::handle, NetworkDirection.PLAY_TO_SERVER);
        register(2, NetworkItemExtractPayload.class, NetworkItemExtractPayload.STREAM_CODEC,
                NetworkItemExtractPayload::handle, NetworkDirection.PLAY_TO_SERVER);
        register(3, ServerConfigSyncPayload.class, ServerConfigSyncPayload.STREAM_CODEC,
                ServerConfigSyncPayload::handle, NetworkDirection.PLAY_TO_CLIENT);
        register(CraftingCalculationProgressPayload.PACKET_ID, CraftingCalculationProgressPayload.class, CraftingCalculationProgressPayload.STREAM_CODEC,
                CraftingCalculationProgressPayload::handle, NetworkDirection.PLAY_TO_CLIENT);
        register(CraftingCalculationPathPayload.PACKET_ID, CraftingCalculationPathPayload.class, CraftingCalculationPathPayload.STREAM_CODEC,
                CraftingCalculationPathPayload::handle, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static <T> void register(int id, Class<T> type, PacketCodec<FriendlyByteBuf, T> codec,
            BiConsumer<T, Player> handler, NetworkDirection direction) {
        CHANNEL.messageBuilder(type, id, direction)
                .encoder((payload, buffer) -> codec.encode(buffer, payload))
                .decoder(codec::decode)
                .consumerMainThread((payload, contextSupplier) -> {
                    var context = contextSupplier.get();
                    if (direction == NetworkDirection.PLAY_TO_SERVER) {
                        var player = context.getSender();
                        if (player != null) handler.accept(payload, player);
                    } else {
                        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                                () -> () -> ClientNetworkHandler.accept(payload, handler));
                    }
                    context.setPacketHandled(true);
                }).add();
    }

    public static void sendToServer(Object payload) { CHANNEL.sendToServer(payload); }
    public static void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }
}
