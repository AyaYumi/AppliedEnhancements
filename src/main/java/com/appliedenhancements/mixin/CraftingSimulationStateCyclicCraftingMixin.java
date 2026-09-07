package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.config.Actionable;
import com.github.appliedenhancements.integration.ae2.AelisIgnoredSeedInventory;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.github.appliedenhancements.crafting.aelis.AelisCyclicCraftingAmounts;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftingTracker;
import com.github.appliedenhancements.integration.ae2.AelisCycleExecutionPlanCarrier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingSimulationState.class, remap = false)
public abstract class CraftingSimulationStateCyclicCraftingMixin
        implements AelisCyclicCraftingTracker, AelisIgnoredSeedInventory, AelisCalculationPathCarrier {
    @Unique private final Map<AEKey, Long> appliedenhancements$ignoredSeeds = new LinkedHashMap<>();
    @Unique private AelisCalculationPath appliedenhancements$apiPath;

    @Override public Map<AEKey, Long> appliedenhancements$ignoredSeeds() { return appliedenhancements$ignoredSeeds; }
    @Override public AelisCalculationPath molecularmanipulator$getCalculationPath() { return appliedenhancements$apiPath; }
    @Override public void molecularmanipulator$setCalculationPath(AelisCalculationPath path) { appliedenhancements$apiPath = path; }

    @Inject(method = "ignore", at = @At("HEAD"))
    private void appliedenhancements$rememberIgnoredStock(AEKey key, CallbackInfo callback) {
        long available = ((CraftingSimulationState) (Object) this).extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        appliedenhancements$ignoredSeeds.put(key, available);
    }

    @Unique
    private final Map<AEKey, Long> appliedenhancements$cyclicCraftAmounts =
            new LinkedHashMap<>();
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

    @Inject(method = "applyDiff", at = @At("RETURN"))
    private void appliedenhancements$mergeCyclicCraftingIntoParent(
            CraftingSimulationState parent, CallbackInfo callback) {
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
        var tracker = (AelisCyclicCraftingTracker) state;
        if (tracker.appliedenhancements$getCycleExecutionPlan() != null
                || ((AelisCalculationPathCarrier) state).molecularmanipulator$getCalculationPath() == AelisCalculationPath.AELIS) {
            callback.setReturnValue((CraftingPlan) AelisCycleExecutionApi.attachToPlan(
                    state, callback.getReturnValue()));
        } else {
            ((AelisCyclicCraftAmountsCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setCyclicCraftAmounts(tracker.appliedenhancements$getCyclicCraftAmounts());
            ((AelisCycleExecutionPlanCarrier) (Object) callback.getReturnValue())
                    .appliedenhancements$setCycleExecutionPlan(null);
        }
    }
}
