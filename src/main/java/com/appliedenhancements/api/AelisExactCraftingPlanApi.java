package com.appliedenhancements.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

/** Read-only exact execution metadata for crafting CPU integrations. */
public final class AelisExactCraftingPlanApi {
    private AelisExactCraftingPlanApi() {
    }

    /** Reads all exact and projected quantities through the stable public API. */
    public static AelisPlanMetadata read(ICraftingPlan plan) {
        return AelisPlanMetadata.read(plan);
    }

    /** Reports which exact quantities a CPU must support for this plan. */
    public static AelisExecutionRequirement executionRequirement(ICraftingPlan plan) {
        return read(plan).executionRequirement();
    }

    /** Attach execution quantities to a plan produced by any planner. Keep the returned plan. */
    public static ICraftingPlan attachExecutionMetadata(ICraftingPlan plan, BigInteger output,
            Map<IPatternDetails, BigInteger> tasks, Map<AEKey, BigInteger> infiniteInputs) {
        new AelisExactRequest(output);
        Objects.requireNonNull(tasks).forEach((key, value) -> {
            if (key == null || value == null || value.signum() <= 0) throw new IllegalArgumentException("Invalid task");
        });
        Objects.requireNonNull(infiniteInputs).forEach((key, value) -> {
            if (key == null || value == null || value.signum() <= 0) throw new IllegalArgumentException("Invalid infinite input");
        });
        var result = AelisCycleExecutionApi.copyMetadata(plan, plan);
        var carrier = (AelisBigIntegerCraftAmountsCarrier) result;
        carrier.appliedenhancements$setBigIntegerFinalAmount(output);
        carrier.appliedenhancements$setBigIntegerPatternTimes(Map.copyOf(tasks));
        carrier.appliedenhancements$setBigIntegerInfiniteAmounts(Map.copyOf(infiniteInputs));
        return AelisCycleExecutionApi.copyMetadata(result, result);
    }

    public static Map<IPatternDetails, BigInteger> getPatternTimes(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof AelisBigIntegerCraftAmountsCarrier carrier) {
            var values = carrier.appliedenhancements$getBigIntegerPatternTimes();
            // This is a complete ledger, not a sparse overflow patch. Merging a rewritten
            // long projection here counts both the original and scaled recipe as separate work.
            if (values != null && !values.isEmpty()) return Map.copyOf(values);
        }
        var result = new java.util.LinkedHashMap<IPatternDetails, BigInteger>();
        plan.patternTimes().forEach((pattern, times) -> result.put(pattern, BigInteger.valueOf(times)));
        return Map.copyOf(result);
    }

    public static BigInteger getFinalOutputAmount(ICraftingPlan plan) {
        if (plan instanceof AelisBigIntegerCraftAmountsCarrier carrier && carrier.appliedenhancements$getBigIntegerFinalAmount() != null)
            return carrier.appliedenhancements$getBigIntegerFinalAmount();
        return BigInteger.valueOf(plan.finalOutput().amount());
    }

    public static BigInteger getBytes(ICraftingPlan plan) {
        if (plan instanceof AelisBigIntegerCraftAmountsCarrier carrier && carrier.appliedenhancements$getBigIntegerBytes() != null)
            return carrier.appliedenhancements$getBigIntegerBytes();
        return BigInteger.valueOf(plan.bytes());
    }

    public static Map<AEKey, BigInteger> getStoredAmounts(ICraftingPlan plan) {
        var result = new java.util.LinkedHashMap<AEKey, BigInteger>();
        plan.usedItems().forEach(entry -> result.put(entry.getKey(), BigInteger.valueOf(entry.getLongValue())));
        if (plan instanceof AelisBigIntegerCraftAmountsCarrier carrier) result.putAll(carrier.appliedenhancements$getBigIntegerStoredAmounts());
        return Map.copyOf(result);
    }

    public static Map<AEKey, BigInteger> getInfiniteInputAmounts(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof AelisBigIntegerCraftAmountsCarrier carrier) {
            var values = carrier.appliedenhancements$getBigIntegerInfiniteAmounts();
            return values == null ? Map.of() : Map.copyOf(values);
        }
        return Map.of();
    }

    public static boolean requiresExactExecution(ICraftingPlan plan) {
        // Preserve the legacy query contract. The structured query also reports
        // aggregate outputs, infinite inputs and projection metadata.
        var max = BigInteger.valueOf(Long.MAX_VALUE);
        return getFinalOutputAmount(plan).compareTo(max) > 0
                || getStoredAmounts(plan).values().stream().anyMatch(value -> value.compareTo(max) > 0)
                || getPatternTimes(plan).values().stream().anyMatch(value -> value.compareTo(max) > 0);
    }
}
