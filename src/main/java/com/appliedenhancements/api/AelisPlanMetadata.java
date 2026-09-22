package com.appliedenhancements.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import java.math.BigInteger;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Immutable quantity snapshot. Native long values are promoted when exact metadata
 * is absent; this cannot recover arithmetic already lost by another planner.
 * Pattern/key objects retain their original identities.
 */
public record AelisPlanMetadata(
        BigInteger finalOutputAmount,
        BigInteger bytes,
        Map<AEKey, BigInteger> craftedAmounts,
        Map<AEKey, BigInteger> missingAmounts,
        Map<AEKey, BigInteger> storedAmounts,
        Map<AEKey, BigInteger> infiniteInputs,
        Map<IPatternDetails, BigInteger> patternTimes,
        AelisPlanPath path,
        boolean projectionSaturated) {
    public AelisPlanMetadata {
        Objects.requireNonNull(finalOutputAmount, "finalOutputAmount");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(craftedAmounts, "craftedAmounts");
        Objects.requireNonNull(missingAmounts, "missingAmounts");
        Objects.requireNonNull(storedAmounts, "storedAmounts");
        Objects.requireNonNull(infiniteInputs, "infiniteInputs");
        Objects.requireNonNull(patternTimes, "patternTimes");
        Objects.requireNonNull(path, "path");
        if (finalOutputAmount.signum() <= 0 || bytes.signum() < 0) {
            throw new IllegalArgumentException("Invalid crafting output or byte estimate");
        }
        craftedAmounts = Map.copyOf(craftedAmounts);
        missingAmounts = Map.copyOf(missingAmounts);
        storedAmounts = Map.copyOf(storedAmounts);
        infiniteInputs = Map.copyOf(infiniteInputs);
        patternTimes = Map.copyOf(patternTimes);
        requireNonNegative(craftedAmounts);
        requireNonNegative(missingAmounts);
        requireNonNegative(storedAmounts);
        requireNonNegative(infiniteInputs);
        requireNonNegative(patternTimes);
    }

    private static void requireNonNegative(Map<?, BigInteger> values) {
        if (values.values().stream().anyMatch(value -> value.signum() < 0)) {
            throw new IllegalArgumentException("Negative crafting quantity");
        }
    }

    public AelisExecutionRequirement executionRequirement() {
        var max = BigInteger.valueOf(Long.MAX_VALUE);
        return new AelisExecutionRequirement(
                finalOutputAmount.compareTo(max) > 0,
                patternTimes.values().stream().anyMatch(value -> value.compareTo(max) > 0),
                storedAmounts.values().stream().anyMatch(value -> value.compareTo(max) > 0),
                infiniteInputs.values().stream().anyMatch(value -> value.compareTo(max) > 0),
                bytes.compareTo(max) > 0,
                craftedAmounts.values().stream().anyMatch(value -> value.compareTo(max) > 0),
                missingAmounts.values().stream().anyMatch(value -> value.compareTo(max) > 0),
                projectionSaturated);
    }

    static AelisPlanMetadata read(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        var carrier = plan instanceof AelisBigIntegerCraftAmountsCarrier exact ? exact : null;
        var sourcePath = plan instanceof AelisCalculationPathCarrier source
                ? source.molecularmanipulator$getCalculationPath() : null;
        var path = sourcePath != null ? mapPath(sourcePath)
                : plan instanceof appeng.crafting.CraftingPlan ? AelisPlanPath.NATIVE : AelisPlanPath.EXTERNAL;
        var tasks = AelisExactCraftingPlanApi.getPatternTimes(plan);
        var crafted = new LinkedHashMap<AEKey, BigInteger>();
        tasks.forEach((pattern, count) -> {
            for (var output : pattern.getOutputs()) {
                crafted.merge(output.what(), count.multiply(BigInteger.valueOf(output.amount())), BigInteger::add);
            }
        });
        var missing = new LinkedHashMap<AEKey, BigInteger>();
        plan.missingItems().forEach(entry -> missing.put(entry.getKey(), BigInteger.valueOf(entry.getLongValue())));
        // Material maps are sparse exact overrides; task counts above are a complete ledger.
        if (carrier != null) {
            crafted.putAll(carrier.appliedenhancements$getBigIntegerCraftAmounts());
            missing.putAll(carrier.appliedenhancements$getBigIntegerMissingAmounts());
        }
        return new AelisPlanMetadata(
                carrier != null && carrier.appliedenhancements$getBigIntegerFinalAmount() != null
                        ? carrier.appliedenhancements$getBigIntegerFinalAmount()
                        : BigInteger.valueOf(plan.finalOutput().amount()),
                carrier != null && carrier.appliedenhancements$getBigIntegerBytes() != null
                        ? carrier.appliedenhancements$getBigIntegerBytes()
                        : BigInteger.valueOf(plan.bytes()),
                crafted,
                missing,
                AelisExactCraftingPlanApi.getStoredAmounts(plan),
                AelisExactCraftingPlanApi.getInfiniteInputAmounts(plan),
                tasks,
                path,
                carrier != null && carrier.appliedenhancements$isPreviewOnly());
    }

    private static AelisPlanPath mapPath(AelisCalculationPath path) {
        return switch (path) {
            case AELIS -> AelisPlanPath.AELIS;
            case AE2_FALLBACK -> AelisPlanPath.AE2_FALLBACK;
            case EXTERNAL -> AelisPlanPath.EXTERNAL;
            case AE2_NATIVE -> AelisPlanPath.NATIVE;
        };
    }
}
