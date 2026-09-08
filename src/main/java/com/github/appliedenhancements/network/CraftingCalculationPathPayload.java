package com.github.appliedenhancements.network;

import com.appliedenhancements.network.PacketCodec;
import com.appliedenhancements.AppliedEnhancements;
import appeng.api.stacks.AEKey;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import io.netty.handler.codec.DecoderException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

public record CraftingCalculationPathPayload(
        int containerId,
        AelisCalculationPath path,
        Map<AEKey, Long> cyclicCraftAmounts) {
    private static final int MAX_CYCLIC_AMOUNT_ENTRIES = 1_000_000;
    public static final int PACKET_ID = 5;
    public static final PacketCodec<FriendlyByteBuf, CraftingCalculationPathPayload> STREAM_CODEC =
            PacketCodec.of(CraftingCalculationPathPayload::encode, CraftingCalculationPathPayload::decode);

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


    public static void encode(FriendlyByteBuf buffer, CraftingCalculationPathPayload payload) {
        buffer.writeVarInt(payload.containerId);
        buffer.writeByte(payload.path.networkId());
        buffer.writeVarInt(payload.cyclicCraftAmounts.size());
        for (var entry : payload.cyclicCraftAmounts.entrySet()) {
            AEKey.writeKey(buffer, entry.getKey());
            buffer.writeVarLong(entry.getValue());
        }
    }

    public static CraftingCalculationPathPayload decode(FriendlyByteBuf buffer) {
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

    public static void handle(CraftingCalculationPathPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
        var menu = receivingPlayer.containerMenu;
        if (menu.containerId == payload.containerId
                && menu instanceof AelisCalculationPathMenuBridge bridge) {
            bridge.molecularmanipulator$setCalculationPath(payload.path);
            bridge.appliedenhancements$setCyclicCraftAmounts(
                    payload.cyclicCraftAmounts);
        }
    }

}
