package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.api.PatternBatchMoveApi;
import com.appliedenhancements.api.PatternSlotRef;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-bound request to atomically move selected pattern slots. */
public record PatternBatchMovePayload(
        PatternBatchMoveApi.Request request) implements CustomPacketPayload {
    public static final Type<PatternBatchMovePayload> TYPE =
            new Type<>(AppliedEnhancements.id("pattern_batch_move"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PatternBatchMovePayload> STREAM_CODEC =
            StreamCodec.of(PatternBatchMovePayload::encode, PatternBatchMovePayload::decode);

    public PatternBatchMovePayload {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, PatternBatchMovePayload payload) {
        var request = payload.request();
        buffer.writeVarInt(request.menuId());
        buffer.writeVarInt(request.sources().size());
        for (PatternSlotRef source : request.sources()) {
            buffer.writeVarLong(source.containerId());
            buffer.writeVarInt(source.slot());
        }
        buffer.writeVarInt(request.targetContainerIds().size());
        for (long targetId : request.targetContainerIds()) {
            buffer.writeVarLong(targetId);
        }
        buffer.writeVarInt(request.preferredTargetSlot() + 1);
    }

    private static PatternBatchMovePayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        int sourceCount = buffer.readVarInt();
        if (containerId < 0 || sourceCount <= 0
                || sourceCount > PatternBatchMoveApi.MAX_SOURCES) {
            throw new DecoderException("Invalid batch pattern source count");
        }
        var sources = new ArrayList<PatternSlotRef>(sourceCount);
        for (int index = 0; index < sourceCount; index++) {
            long sourceContainerId = buffer.readVarLong();
            int sourceSlot = buffer.readVarInt();
            if (sourceSlot < 0) {
                throw new DecoderException("Invalid batch pattern source slot");
            }
            sources.add(new PatternSlotRef(sourceContainerId, sourceSlot));
        }

        int targetCount = buffer.readVarInt();
        if (targetCount <= 0 || targetCount > PatternBatchMoveApi.MAX_TARGETS) {
            throw new DecoderException("Invalid batch pattern target count");
        }
        var targets = new ArrayList<Long>(targetCount);
        for (int index = 0; index < targetCount; index++) {
            targets.add(buffer.readVarLong());
        }
        int preferredTargetSlot = buffer.readVarInt() - 1;
        if (preferredTargetSlot < -1) {
            throw new DecoderException("Invalid preferred pattern target slot");
        }
        return new PatternBatchMovePayload(new PatternBatchMoveApi.Request(
                containerId, sources, targets, preferredTargetSlot));
    }

    public static void handle(PatternBatchMovePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PatternBatchMoveApi.Result result = PatternBatchMoveApi.execute(
                    player, payload.request());
            if (result.success()) {
                player.sendSystemMessage(Component.translatable(
                        "message.appliedenhancements.pattern_batch_move.success",
                        result.moved()));
            } else {
                player.sendSystemMessage(Component.translatable(
                        "message.appliedenhancements.pattern_batch_move.failure."
                                + failureKey(result.failure())));
            }
        });
    }

    private static String failureKey(PatternBatchMoveApi.Failure failure) {
        return switch (failure) {
            case INVALID_MENU -> "invalid_menu";
            case INVALID_SOURCE -> "invalid_source";
            case INVALID_TARGET -> "invalid_target";
            case SAME_TARGET -> "same_target";
            case NOT_ENOUGH_SPACE -> "not_enough_space";
            case APPLY_FAILED -> "apply_failed";
            case NONE -> "unknown";
        };
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
