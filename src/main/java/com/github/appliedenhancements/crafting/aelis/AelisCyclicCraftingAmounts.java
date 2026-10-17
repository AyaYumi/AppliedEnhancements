package com.github.appliedenhancements.crafting.aelis;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.appliedenhancements.util.SaturatingLongMath;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Aggregates per-output item amounts produced by cyclic pattern firings. */
public final class AelisCyclicCraftingAmounts {
    private AelisCyclicCraftingAmounts() {
    }

    public static void addOutputs(
            Map<AEKey, Long> totals,
            Iterable<GenericStack> outputs,
            long patternTimes) {
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(outputs, "outputs");
        if (patternTimes <= 0) {
            return;
        }
        for (GenericStack output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0) {
                continue;
            }
            long produced = SaturatingLongMath.multiply(output.amount(), patternTimes);
            totals.merge(
                    output.what(), produced,
                    SaturatingLongMath::add);
        }
    }

    public static void merge(
            Map<AEKey, Long> totals,
            Map<AEKey, Long> addition) {
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(addition, "addition");
        for (var entry : addition.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && entry.getValue() > 0) {
                totals.merge(
                        entry.getKey(), entry.getValue(),
                        SaturatingLongMath::add);
            }
        }
    }

    public static void addExactOutputs(Map<AEKey, BigInteger> totals,
            Iterable<GenericStack> outputs, BigInteger patternTimes) {
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(outputs, "outputs");
        Objects.requireNonNull(patternTimes, "patternTimes");
        if (patternTimes.signum() <= 0) return;
        for (var output : outputs) {
            if (output != null && output.what() != null && output.amount() > 0) {
                totals.merge(output.what(), BigInteger.valueOf(output.amount()).multiply(patternTimes), BigInteger::add);
            }
        }
    }

    public static void mergeExact(Map<AEKey, BigInteger> totals, Map<AEKey, BigInteger> addition) {
        addition.forEach((key, amount) -> {
            if (key != null && amount != null && amount.signum() > 0) {
                totals.merge(key, amount, BigInteger::add);
            }
        });
    }

    /** Exact overrides supplement the native amounts; ordinary crafting totals are unrelated. */
    public static Map<AEKey, BigInteger> exactAmounts(Map<AEKey, Long> projected,
            Map<AEKey, BigInteger> exact) {
        var amounts = new LinkedHashMap<AEKey, BigInteger>();
        projected.forEach((key, amount) -> amounts.put(key, BigInteger.valueOf(amount)));
        amounts.putAll(exact);
        return Map.copyOf(amounts);
    }

    public static Map<AEKey, Long> project(Map<AEKey, BigInteger> exact) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        exact.forEach((key, amount) -> amounts.put(key, AelisBigIntegerMath.saturatingLong(amount)));
        return Map.copyOf(amounts);
    }

    /** Only overflow entries need extra storage and wire transport. */
    public static Map<AEKey, BigInteger> overflowingAmounts(Map<AEKey, BigInteger> exact) {
        var amounts = new LinkedHashMap<AEKey, BigInteger>();
        exact.forEach((key, amount) -> {
            if (amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) amounts.put(key, amount);
        });
        return Map.copyOf(amounts);
    }
}
