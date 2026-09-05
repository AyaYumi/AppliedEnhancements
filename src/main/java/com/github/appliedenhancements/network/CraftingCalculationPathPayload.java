package com.github.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import appeng.api.stacks.AEKey;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import io.netty.handler.codec.DecoderException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CraftingCalculationPathPayload(
        int containerId,
        AelisCalculationPath path,
        Map<AEKey, Long> cyclicCraftAmounts)
        implements CustomPacketPayload {
    private static final int MAX_CYCLIC_AMOUNT_ENTRIES = 1_000_000;
    public static final Type<CraftingCalculationPathPayload> TYPE =
            new Type<>(AppliedEnhancements.id("aelis_calculation_path"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingCalculationPathPayload> STREAM_CODEC =
            StreamCodec.of(CraftingCalculationPathPayload::encode, CraftingCalculationPathPayload::decode);

    public CraftingCalculationPathPayload {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(cyclicCraftAmounts, "cyclicCraftAmounts");
        if (cyclicCraftAmounts.size() > MAX_CYCLIC_AMOUNT_ENTRIES) {
            throw new IllegalArgumentException("Too many cyclic craft amount entries");
        }
        var copy = new LinkedHashMap<AEKey, Long>();
        for (var entry : cyclicCraftAmounts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue() <= 0) {
                throw new IllegalArgumentException(
                        "Cyclic craft amounts must use non-null keys and positive values");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        cyclicCraftAmounts = Map.copyOf(copy);
    }

    public static void register(net.neoforged.neoforge.network.registration.PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, CraftingCalculationPathPayload::handle);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CraftingCalculationPathPayload payload) {
        buffer.writeVarInt(payload.containerId);
        buffer.writeByte(payload.path.networkId());
        buffer.writeVarInt(payload.cyclicCraftAmounts.size());
        for (var entry : payload.cyclicCraftAmounts.entrySet()) {
            AEKey.writeKey(buffer, entry.getKey());
            buffer.writeVarLong(entry.getValue());
        }
    }

    private static CraftingCalculationPathPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        if (containerId < 0) {
            throw new DecoderException("Crafting calculation path containerId must be non-negative");
        }
        int pathId = buffer.readUnsignedByte();
        int amountCount = buffer.readVarInt();
        if (amountCount < 0 || amountCount > MAX_CYCLIC_AMOUNT_ENTRIES) {
            throw new DecoderException("Invalid cyclic craft amount entry count");
        }
        try {
            var cyclicCraftAmounts = new LinkedHashMap<AEKey, Long>();
            for (int index = 0; index < amountCount; index++) {
                AEKey key = AEKey.readKey(buffer);
                long amount = buffer.readVarLong();
                if (key == null || amount <= 0
                        || cyclicCraftAmounts.putIfAbsent(key, amount) != null) {
                    throw new DecoderException("Invalid cyclic craft amount entry");
                }
            }
            return new CraftingCalculationPathPayload(
                    containerId,
                    AelisCalculationPath.fromNetworkId(pathId),
                    cyclicCraftAmounts);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException(exception);
        }
    }

    private static void handle(CraftingCalculationPathPayload payload, IPayloadContext context) {
        var menu = context.player().containerMenu;
        if (menu.containerId == payload.containerId
                && menu instanceof AelisCalculationPathMenuBridge bridge) {
            bridge.molecularmanipulator$setCalculationPath(payload.path);
            bridge.appliedenhancements$setCyclicCraftAmounts(
                    payload.cyclicCraftAmounts);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
