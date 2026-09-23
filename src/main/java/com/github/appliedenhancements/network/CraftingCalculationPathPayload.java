package com.github.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import appeng.api.stacks.AEKey;
import com.appliedenhancements.network.PacketCodec;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import io.netty.handler.codec.DecoderException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigInteger;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

public record CraftingCalculationPathPayload(
        int containerId,
        AelisCalculationPath path,
        Map<AEKey, Long> cyclicCraftAmounts,
        Map<AEKey, BigInteger> bigIntegerCraftAmounts,
        Map<AEKey, BigInteger> bigIntegerMissingAmounts,
        Map<AEKey, BigInteger> bigIntegerStoredAmounts,
        BigInteger bigIntegerBytes, BigInteger finalOutputAmount)
         {
    public CraftingCalculationPathPayload(int containerId, AelisCalculationPath path,
            Map<AEKey, Long> cyclic, Map<AEKey, BigInteger> crafted, Map<AEKey, BigInteger> missing,
            Map<AEKey, BigInteger> stored, BigInteger bytes) {
        this(containerId, path, cyclic, crafted, missing, stored, bytes, null);
    }
    private static final int MAX_CYCLIC_AMOUNT_ENTRIES = 1_000_000;
    private static final int MAX_BIG_INTEGER_ENTRIES = 1_024;
    private static final int MAX_BIG_INTEGER_BYTES = 65_536;
    private static final int MAX_BIG_INTEGER_TOTAL_BYTES = 262_144;
    public static final int PACKET_ID = 5;
    public static final PacketCodec<FriendlyByteBuf, CraftingCalculationPathPayload> STREAM_CODEC =
            PacketCodec.of(CraftingCalculationPathPayload::encode, CraftingCalculationPathPayload::decode);

    public CraftingCalculationPathPayload(int containerId, AelisCalculationPath path,
            Map<AEKey, Long> cyclicCraftAmounts, Map<AEKey, BigInteger> bigIntegerCraftAmounts) {
        this(containerId, path, cyclicCraftAmounts, bigIntegerCraftAmounts, Map.of());
    }

    public CraftingCalculationPathPayload(int containerId, AelisCalculationPath path,
            Map<AEKey, Long> cyclicCraftAmounts, Map<AEKey, BigInteger> bigIntegerCraftAmounts,
            Map<AEKey, BigInteger> bigIntegerMissingAmounts) {
        this(containerId, path, cyclicCraftAmounts, bigIntegerCraftAmounts, bigIntegerMissingAmounts, Map.of());
    }

    public CraftingCalculationPathPayload(int containerId, AelisCalculationPath path,
            Map<AEKey, Long> cyclicCraftAmounts, Map<AEKey, BigInteger> bigIntegerCraftAmounts,
            Map<AEKey, BigInteger> bigIntegerMissingAmounts, Map<AEKey, BigInteger> bigIntegerStoredAmounts) {
        this(containerId, path, cyclicCraftAmounts, bigIntegerCraftAmounts, bigIntegerMissingAmounts,
                bigIntegerStoredAmounts, null);
    }

    public CraftingCalculationPathPayload {
        if (finalOutputAmount != null) new com.appliedenhancements.api.AelisExactRequest(finalOutputAmount);
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(cyclicCraftAmounts, "cyclicCraftAmounts");
        Objects.requireNonNull(bigIntegerCraftAmounts, "bigIntegerCraftAmounts");
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
        int[] budget = {0};
        bigIntegerCraftAmounts = copyExactAmounts(bigIntegerCraftAmounts, budget);
        bigIntegerMissingAmounts = copyExactAmounts(bigIntegerMissingAmounts, budget);
        bigIntegerStoredAmounts = copyExactAmounts(bigIntegerStoredAmounts, budget);
        if (bigIntegerBytes != null) {
            int byteCount = bigIntegerBytes.toByteArray().length;
            if (bigIntegerBytes.signum() < 0 || byteCount > MAX_BIG_INTEGER_BYTES
                    || budget[0] > MAX_BIG_INTEGER_TOTAL_BYTES - byteCount) {
                throw new IllegalArgumentException("Exact storage bytes exceed payload budget or are negative");
            }
        }
    }

    private static Map<AEKey, BigInteger> copyExactAmounts(Map<AEKey, BigInteger> amounts, int[] budget) {
        Objects.requireNonNull(amounts, "amounts");
        if (amounts.size() > MAX_BIG_INTEGER_ENTRIES) {
            throw new IllegalArgumentException("Too many BigInteger craft amount entries");
        }
        var exactCopy = new LinkedHashMap<AEKey, BigInteger>();
        for (var entry : amounts.entrySet()) {
            int entryBytes = entry.getValue() == null
                    ? 0
                    : entry.getValue().toByteArray().length;
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().signum() <= 0
                    || entryBytes > MAX_BIG_INTEGER_BYTES
                    || budget[0] > MAX_BIG_INTEGER_TOTAL_BYTES - entryBytes) {
                throw new IllegalArgumentException(
                        "BigInteger craft amounts must use bounded positive values");
            }
            budget[0] += entryBytes;
            exactCopy.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(exactCopy);
    }


    private static void encode(FriendlyByteBuf buffer, CraftingCalculationPathPayload payload) {
        buffer.writeVarInt(payload.containerId);
        buffer.writeByte(payload.path.networkId());
        buffer.writeVarInt(payload.cyclicCraftAmounts.size());
        for (var entry : payload.cyclicCraftAmounts.entrySet()) {
            AEKey.writeKey(buffer, entry.getKey());
            buffer.writeVarLong(entry.getValue());
        }
        buffer.writeVarInt(payload.bigIntegerCraftAmounts.size());
        for (var entry : payload.bigIntegerCraftAmounts.entrySet()) {
            AEKey.writeKey(buffer, entry.getKey());
            buffer.writeByteArray(entry.getValue().toByteArray());
        }
        buffer.writeVarInt(payload.bigIntegerMissingAmounts.size());
        for (var entry : payload.bigIntegerMissingAmounts.entrySet()) {
            AEKey.writeKey(buffer, entry.getKey());
            buffer.writeByteArray(entry.getValue().toByteArray());
        }
        buffer.writeVarInt(payload.bigIntegerStoredAmounts.size());
        for (var entry : payload.bigIntegerStoredAmounts.entrySet()) {
            AEKey.writeKey(buffer, entry.getKey());
            buffer.writeByteArray(entry.getValue().toByteArray());
        }
        buffer.writeBoolean(payload.bigIntegerBytes != null);
        if (payload.bigIntegerBytes != null) buffer.writeByteArray(payload.bigIntegerBytes.toByteArray());
        buffer.writeBoolean(payload.finalOutputAmount != null);
        if (payload.finalOutputAmount != null) buffer.writeUtf(payload.finalOutputAmount.toString(), 256);
    }

    private static CraftingCalculationPathPayload decode(FriendlyByteBuf buffer) {
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
            int[] budget = {0};
            var bigIntegerCraftAmounts = decodeExactAmounts(buffer, budget);
            var bigIntegerMissingAmounts = decodeExactAmounts(buffer, budget);
            var bigIntegerStoredAmounts = decodeExactAmounts(buffer, budget);
            BigInteger bigIntegerBytes = null;
            if (buffer.readBoolean()) {
                byte[] encoded = buffer.readByteArray(MAX_BIG_INTEGER_BYTES);
                if (budget[0] > MAX_BIG_INTEGER_TOTAL_BYTES - encoded.length) {
                    throw new DecoderException("Exact storage bytes exceed payload budget");
                }
                bigIntegerBytes = new BigInteger(encoded);
            }
            return new CraftingCalculationPathPayload(
                    containerId, AelisCalculationPath.fromNetworkId(pathId),
                    cyclicCraftAmounts, bigIntegerCraftAmounts, bigIntegerMissingAmounts, bigIntegerStoredAmounts, bigIntegerBytes,
                    buffer.readBoolean() ? new BigInteger(buffer.readUtf(256)) : null);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException(exception);
        }
    }

    private static Map<AEKey, BigInteger> decodeExactAmounts(FriendlyByteBuf buffer, int[] budget) {
        int exactAmountCount = buffer.readVarInt();
        if (exactAmountCount < 0 || exactAmountCount > MAX_BIG_INTEGER_ENTRIES) {
            throw new DecoderException("Invalid BigInteger amount entry count");
        }
        var amounts = new LinkedHashMap<AEKey, BigInteger>();
        for (int index = 0; index < exactAmountCount; index++) {
            AEKey key = AEKey.readKey(buffer);
            byte[] encoded = buffer.readByteArray(MAX_BIG_INTEGER_BYTES);
            if (budget[0] > MAX_BIG_INTEGER_TOTAL_BYTES - encoded.length) {
                throw new DecoderException("BigInteger amounts exceed payload budget");
            }
            budget[0] += encoded.length;
            BigInteger amount = new BigInteger(encoded);
            if (key == null || amount.signum() <= 0 || amounts.putIfAbsent(key, amount) != null) {
                throw new DecoderException("Invalid BigInteger amount entry");
            }
        }
        return amounts;
    }

    public static void handle(CraftingCalculationPathPayload payload, net.minecraft.world.entity.player.Player receivingPlayer) {
        var menu = receivingPlayer.containerMenu;
        if (menu.containerId == payload.containerId
                && menu instanceof AelisCalculationPathMenuBridge bridge) {
            bridge.molecularmanipulator$setCalculationPath(payload.path);
            bridge.appliedenhancements$setBigIntegerFinalAmount(payload.finalOutputAmount);
            bridge.appliedenhancements$setCyclicCraftAmounts(
                    payload.cyclicCraftAmounts);
            bridge.appliedenhancements$setBigIntegerCraftAmounts(
                    payload.bigIntegerCraftAmounts);
            bridge.appliedenhancements$setBigIntegerMissingAmounts(payload.bigIntegerMissingAmounts);
            bridge.appliedenhancements$setBigIntegerStoredAmounts(payload.bigIntegerStoredAmounts);
            bridge.appliedenhancements$setBigIntegerBytes(payload.bigIntegerBytes);
        }
    }


}
