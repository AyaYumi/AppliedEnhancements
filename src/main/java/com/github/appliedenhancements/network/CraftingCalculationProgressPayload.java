package com.github.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressPhase;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CraftingCalculationProgressPayload(
        int containerId,
        CraftingCalculationProgressSnapshot progress)
        implements CustomPacketPayload {
    public static final Type<CraftingCalculationProgressPayload> TYPE =
            new Type<>(AppliedEnhancements.id("aelis_calculation_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingCalculationProgressPayload>
            STREAM_CODEC = StreamCodec.of(
                    CraftingCalculationProgressPayload::encode,
                    CraftingCalculationProgressPayload::decode);

    public CraftingCalculationProgressPayload {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        Objects.requireNonNull(progress, "progress");
    }

    public static void register(net.neoforged.neoforge.network.registration.PayloadRegistrar registrar) {
        registrar.playToClient(
                TYPE, STREAM_CODEC, CraftingCalculationProgressPayload::handle);
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer,
            CraftingCalculationProgressPayload payload) {
        var progress = payload.progress;
        buffer.writeVarInt(payload.containerId);
        buffer.writeLong(progress.generation());
        buffer.writeLong(progress.revision());
        buffer.writeByte(progress.phase().networkId());
        buffer.writeByte(progress.path().networkId());
        buffer.writeLong(progress.processedSteps());
        buffer.writeLong(progress.discoveredNodes());
        buffer.writeLong(progress.completedUnits());
        buffer.writeLong(progress.totalUnits());
        buffer.writeLong(progress.elapsedMillis());
        buffer.writeVarInt(progress.attempt());
        buffer.writeBoolean(progress.simulation());
    }

    private static CraftingCalculationProgressPayload decode(
            RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        long generation = buffer.readLong();
        long revision = buffer.readLong();
        int phaseId = buffer.readUnsignedByte();
        int pathId = buffer.readUnsignedByte();
        long processedSteps = buffer.readLong();
        long discoveredNodes = buffer.readLong();
        long completedUnits = buffer.readLong();
        long totalUnits = buffer.readLong();
        long elapsedMillis = buffer.readLong();
        int attempt = buffer.readVarInt();
        boolean simulation = buffer.readBoolean();

        if (containerId < 0 || generation <= 0 || revision <= 0
                || processedSteps < 0 || discoveredNodes < 0
                || completedUnits < 0 || elapsedMillis < 0 || attempt < 0
                || totalUnits < -1 || totalUnits >= 0 && completedUnits > totalUnits) {
            throw new DecoderException("Invalid crafting calculation progress values");
        }
        CraftingCalculationProgressPhase phase;
        try {
            phase = CraftingCalculationProgressPhase.fromNetworkId(phaseId);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException(exception);
        }
        if (phase == CraftingCalculationProgressPhase.IDLE) {
            throw new DecoderException("IDLE progress must not be sent over the network");
        }

        AelisCalculationPath path;
        try {
            path = AelisCalculationPath.fromNetworkId(pathId);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException(exception);
        }
        var progress = new CraftingCalculationProgressSnapshot(
                generation,
                revision,
                phase,
                path,
                processedSteps,
                discoveredNodes,
                completedUnits,
                totalUnits,
                elapsedMillis,
                attempt,
                simulation);
        return new CraftingCalculationProgressPayload(containerId, progress);
    }

    private static void handle(
            CraftingCalculationProgressPayload payload,
            IPayloadContext context) {
        var menu = context.player().containerMenu;
        if (menu.containerId == payload.containerId
                && menu instanceof CraftingCalculationProgressMenuBridge bridge) {
            bridge.molecularmanipulator$acceptCalculationProgress(payload.progress);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
