package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.config.Actionable;
import com.github.appliedenhancements.integration.ae2.AelisIgnoredSeedInventory;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftingTracker;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.github.appliedenhancements.crafting.aelis.AelisCyclicCraftingAmounts;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftingTracker;
import com.github.appliedenhancements.integration.ae2.AelisCycleExecutionPlanCarrier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

@Mixin(value = CraftingSimulationState.class, remap = false)
public abstract class CraftingSimulationStateCyclicCraftingMixin
        implements AelisCyclicCraftingTracker, AelisIgnoredSeedInventory,
        AelisCalculationPathCarrier, AelisBigIntegerCraftingTracker {
    @Unique private final Map<AEKey, Long> appliedenhancements$ignoredSeeds = new LinkedHashMap<>();
    @Unique private AelisCalculationPath appliedenhancements$apiPath;

    @Override public BigInteger appliedenhancements$getBigIntegerBytes() {
        return ((com.github.appliedenhancements.integration.ae2.AelisCraftingBytesTracker) this)
                .appliedenhancements$getExactByteEstimate().ceil();
    }

    @Override public Map<AEKey, Long> appliedenhancements$ignoredSeeds() { return appliedenhancements$ignoredSeeds; }
    @Override public AelisCalculationPath molecularmanipulator$getCalculationPath() { return appliedenhancements$apiPath; }
    @Override public void molecularmanipulator$setCalculationPath(AelisCalculationPath path) { appliedenhancements$apiPath = path; }

    @Inject(method = "ignore", at = @At("HEAD"))
    private void appliedenhancements$rememberIgnoredStock(AEKey key, CallbackInfo callback) {
        if (!CraftingPlannerIntervention.enabled()) return;
        long available = ((CraftingSimulationState) (Object) this).extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        appliedenhancements$ignoredSeeds.put(key, available);
    }

    @Unique
    private final Map<AEKey, Long> appliedenhancements$cyclicCraftAmounts =
            new LinkedHashMap<>();
    @Unique
    private final Map<AEKey, BigInteger> appliedenhancements$bigIntegerCraftAmounts =
            new LinkedHashMap<>();
    @Unique
    private int appliedenhancements$projectedCraftingTransferDepth;
    @Unique private final Map<AEKey, BigInteger> appliedenhancements$bigIntegerMissingAmounts = new LinkedHashMap<>();
    @Unique private boolean appliedenhancements$previewOnly;
    @Unique private final Map<IPatternDetails, BigInteger> appliedenhancements$bigIntegerPatternTimes = new LinkedHashMap<>();
    @Override public Map<IPatternDetails, BigInteger> appliedenhancements$getBigIntegerPatternTimes() {
        return Map.copyOf(appliedenhancements$bigIntegerPatternTimes);
    }
    @Override public void appliedenhancements$setBigIntegerPatternTimes(Map<IPatternDetails, BigInteger> times) {
        appliedenhancements$bigIntegerPatternTimes.clear();
        appliedenhancements$bigIntegerPatternTimes.putAll(times);
    }
    @Override public boolean appliedenhancements$isPreviewOnly() { return appliedenhancements$previewOnly; }
    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerStoredAmounts() {
        // The infinite-inventory transaction already merges this ledger exactly once.
        return ((com.appliedenhancements.storage.InfinitePlanningInventory) this)
                .appliedenhancements$infiniteUsedAmounts();
    }
    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerInfiniteAmounts() {
        return ((com.appliedenhancements.storage.InfinitePlanningInventory) this)
                .appliedenhancements$infiniteUsedAmounts();
    }
    @Override public void appliedenhancements$setPreviewOnly(boolean value) { appliedenhancements$previewOnly = value; }

    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() {
        return Map.copyOf(appliedenhancements$bigIntegerMissingAmounts);
    }

    @Override public void appliedenhancements$setBigIntegerMissingAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerMissingAmounts.clear();
        appliedenhancements$bigIntegerMissingAmounts.putAll(amounts);
    }
    @Unique
    private final List<AelisCycleExecutionPlan.Step>
            appliedenhancements$cycleExecutionSteps = new ArrayList<>();
    @Unique
    private final Map<AEKey, Long> appliedenhancements$minimumCycleSeeds =
            new LinkedHashMap<>();
    @Unique
    private final java.util.Set<AEKey> appliedenhancements$protectedCycleKeys =
            new LinkedHashSet<>();
    @Unique
    private AelisCycleSeedPolicy appliedenhancements$cycleSeedPolicy =
            AelisCycleSeedPolicy.MAX_THROUGHPUT;

    @Override
    public void appliedenhancements$recordCyclicCrafting(
            IPatternDetails pattern, long patternTimes) {
        if (pattern != null) {
            AelisCyclicCraftingAmounts.addOutputs(
                    appliedenhancements$cyclicCraftAmounts,
                    pattern.getOutputs(), patternTimes);
        }
    }

    @Override
    public void appliedenhancements$mergeCyclicCraftAmounts(
            Map<AEKey, Long> amounts) {
        AelisCyclicCraftingAmounts.merge(
                appliedenhancements$cyclicCraftAmounts, amounts);
    }

    @Override
    public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() {
        return Map.copyOf(appliedenhancements$cyclicCraftAmounts);
    }

    @Override
    public void appliedenhancements$recordBigIntegerCrafting(
            IPatternDetails pattern, long patternTimes) {
        if (appliedenhancements$projectedCraftingTransferDepth > 0) {
            return;
        }
        appliedenhancements$recordBigIntegerCrafting(
                pattern, BigInteger.valueOf(patternTimes));
    }

    @Override
    public void appliedenhancements$recordBigIntegerCrafting(
            IPatternDetails pattern, BigInteger patternTimes) {
        if (pattern == null || patternTimes == null
                || patternTimes.signum() <= 0) {
            return;
        }
        appliedenhancements$bigIntegerPatternTimes.merge(pattern, patternTimes, BigInteger::add);
        for (var output : pattern.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0) {
                appliedenhancements$bigIntegerCraftAmounts.merge(
                        output.what(),
                        BigInteger.valueOf(output.amount()).multiply(patternTimes),
                        BigInteger::add);
            }
        }
    }

    @Override
    public void appliedenhancements$beginProjectedCraftingTransfer() {
        appliedenhancements$projectedCraftingTransferDepth++;
    }

    @Override
    public void appliedenhancements$endProjectedCraftingTransfer() {
        if (appliedenhancements$projectedCraftingTransferDepth <= 0) {
            throw new IllegalStateException(
                    "Projected crafting transfer scope is not active");
        }
        appliedenhancements$projectedCraftingTransferDepth--;
    }

    @Override
    public void appliedenhancements$mergeBigIntegerCraftAmounts(
            Map<AEKey, BigInteger> amounts) {
        if (amounts != null) {
            amounts.forEach((key, amount) -> {
                if (key != null && amount != null && amount.signum() > 0) {
                    appliedenhancements$bigIntegerCraftAmounts.merge(
                            key, amount, BigInteger::add);
                }
            });
        }
    }

    @Override
    public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() {
        return Map.copyOf(appliedenhancements$bigIntegerCraftAmounts);
    }

    @Override
    public void appliedenhancements$setBigIntegerCraftAmounts(
            Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerCraftAmounts.clear();
        appliedenhancements$mergeBigIntegerCraftAmounts(amounts);
    }

    @Override
    public void appliedenhancements$recordCycleExecutionPlan(
            AelisCycleExecutionPlan plan) {
        if (plan == null) {
            return;
        }
        appliedenhancements$cycleExecutionSteps.addAll(plan.steps());
        for (var entry : plan.minimumSeeds().entrySet()) {
            appliedenhancements$minimumCycleSeeds.merge(
                    entry.getKey(), entry.getValue(), Math::max);
        }
        appliedenhancements$protectedCycleKeys.addAll(plan.protectedKeys());
        if (plan.seedPolicy() == AelisCycleSeedPolicy.PRESERVE_MINIMUM) {
            appliedenhancements$cycleSeedPolicy = AelisCycleSeedPolicy.PRESERVE_MINIMUM;
        }
    }

    @Override
    public AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan() {
        if (appliedenhancements$cycleExecutionSteps.isEmpty()) {
            return null;
        }
        return new AelisCycleExecutionPlan(
                appliedenhancements$cycleExecutionSteps,
                appliedenhancements$minimumCycleSeeds,
                appliedenhancements$protectedCycleKeys,
                appliedenhancements$cycleSeedPolicy);
    }

    @WrapMethod(method = "applyDiff")
    private void appliedenhancements$transferExactCraftingOnce(
            CraftingSimulationState parent, Operation<Void> original) {
        if (!CraftingPlannerIntervention.enabledFor(this)) {
            original.call(parent);
            return;
        }
        try (var scope = CraftingPlannerIntervention.openExplicit()) {
        var parentTracker = (AelisBigIntegerCraftingTracker) parent;
        parentTracker.appliedenhancements$beginProjectedCraftingTransfer();
        try {
            original.call(parent);
        } finally {
            parentTracker.appliedenhancements$endProjectedCraftingTransfer();
        }
        parentTracker.appliedenhancements$mergeBigIntegerCraftAmounts(
                appliedenhancements$getBigIntegerCraftAmounts());
        var patternTimes = new LinkedHashMap<>(parentTracker.appliedenhancements$getBigIntegerPatternTimes());
        appliedenhancements$bigIntegerPatternTimes.forEach((pattern, times) -> patternTimes.merge(pattern, times, BigInteger::add));
        parentTracker.appliedenhancements$setBigIntegerPatternTimes(patternTimes);
        var missing = new LinkedHashMap<>(parentTracker.appliedenhancements$getBigIntegerMissingAmounts());
        appliedenhancements$bigIntegerMissingAmounts.forEach((key, amount) -> missing.merge(key, amount, BigInteger::add));
        parentTracker.appliedenhancements$setBigIntegerMissingAmounts(missing);
        if (appliedenhancements$previewOnly) parentTracker.appliedenhancements$setPreviewOnly(true);
        if (appliedenhancements$apiPath == AelisCalculationPath.AELIS) {
            ((AelisCalculationPathCarrier) parent).molecularmanipulator$setCalculationPath(AelisCalculationPath.AELIS);
        }
        }
    }

    @WrapOperation(method = "applyDiff", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/inv/CraftingSimulationState;addCrafting(Lappeng/api/crafting/IPatternDetails;J)V"))
    private void appliedenhancements$projectMergedPatternCount(CraftingSimulationState parent,
            IPatternDetails pattern, long count, Operation<Void> original) {
        if (!CraftingPlannerIntervention.enabled()) {
            original.call(parent, pattern, count);
            return;
        }
        long current = ((CraftingSimulationStateLongSafetyAccessor) parent)
                .appliedenhancements$getCrafts().getOrDefault(pattern, 0L);
        if (com.appliedenhancements.Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()
                && (appliedenhancements$apiPath == AelisCalculationPath.AELIS || appliedenhancements$previewOnly)
                && count > Long.MAX_VALUE - current && current >= 0 && count >= 0) {
            ((AelisBigIntegerCraftingTracker) parent).appliedenhancements$setPreviewOnly(true);
            original.call(parent, pattern, Long.MAX_VALUE - current);
        } else {
            original.call(parent, pattern, count);
        }
    }

    @Inject(method = "applyDiff", at = @At("RETURN"))
    private void appliedenhancements$mergeCyclicCraftingIntoParent(
            CraftingSimulationState parent, CallbackInfo callback) {
        if (!CraftingPlannerIntervention.enabled()) return;
        ((AelisCyclicCraftingTracker) parent)
                .appliedenhancements$mergeCyclicCraftAmounts(
                        appliedenhancements$getCyclicCraftAmounts());
        ((AelisCyclicCraftingTracker) parent)
                .appliedenhancements$recordCycleExecutionPlan(
                        appliedenhancements$getCycleExecutionPlan());
    }

    @Inject(method = "buildCraftingPlan", at = @At("RETURN"), cancellable = true)
    private static void appliedenhancements$attachCyclicCraftingToPlan(
            CraftingSimulationState state,
            CraftingCalculation calculation,
            long calculatedAmount,
            CallbackInfoReturnable<CraftingPlan> callback) {
        if (!CraftingPlannerIntervention.enabledFor(state)) return;
        var tracker = (AelisCyclicCraftingTracker) state;
        if (tracker.appliedenhancements$getCycleExecutionPlan() != null
                || ((AelisCalculationPathCarrier) state).molecularmanipulator$getCalculationPath() == AelisCalculationPath.AELIS) {
            callback.setReturnValue((CraftingPlan) AelisCycleExecutionApi.attachToPlan(
                    state, callback.getReturnValue()));
        } else {
            boolean previewOnly = ((AelisBigIntegerCraftAmountsCarrier) state).appliedenhancements$isPreviewOnly();
            ((AelisBigIntegerCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setBigIntegerBytes(
                            ((AelisBigIntegerCraftAmountsCarrier) state).appliedenhancements$getBigIntegerBytes());
            ((AelisCyclicCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setCyclicCraftAmounts(tracker.appliedenhancements$getCyclicCraftAmounts());
            ((AelisCycleExecutionPlanCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setCycleExecutionPlan(null);
            ((AelisBigIntegerCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setBigIntegerCraftAmounts(Map.of());
            ((AelisBigIntegerCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setPreviewOnly(previewOnly);
            ((AelisBigIntegerCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setBigIntegerPatternTimes(
                            ((AelisBigIntegerCraftAmountsCarrier) state).appliedenhancements$getBigIntegerPatternTimes());
            ((AelisBigIntegerCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setBigIntegerStoredAmounts(
                            ((AelisBigIntegerCraftAmountsCarrier) state).appliedenhancements$getBigIntegerStoredAmounts());
            ((AelisBigIntegerCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setBigIntegerInfiniteAmounts(
                            ((AelisBigIntegerCraftAmountsCarrier) state).appliedenhancements$getBigIntegerInfiniteAmounts());
        }
    }
}
