package com.appliedenhancements.mixin;

import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingSimulationState.class, remap = false)
abstract class CraftingSimulationStateLongSafetyMixin {
    @Shadow
    @Final
    private KeyCounter unmodifiedCache;

    @Shadow
    @Final
    private KeyCounter modifiableCache;

    @Shadow
    @Final
    private KeyCounter emittedItems;

    @Shadow
    @Final
    private Map<IPatternDetails, Long> crafts;

    @Shadow
    @Final
    private KeyCounter requiredExtract;

    @Inject(method = "insert", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/inv/CraftingSimulationState;cacheFuzzy(Lappeng/api/stacks/AEKey;)V",
            shift = At.Shift.AFTER))
    private void appliedenhancements$rejectUnsafeInventorySum(
            AEKey what, long amount, Actionable mode, CallbackInfo callback) {
        if (mode == Actionable.MODULATE) {
            NativeCraftingLongSafety.addNonNegative(
                    this.modifiableCache.get(what), amount, "simulated inventory amount");
        }
    }

    @Inject(method = "emitItems", at = @At("HEAD"))
    private void appliedenhancements$rejectUnsafeEmittedItemSum(
            AEKey what, long amount, CallbackInfo callback) {
        NativeCraftingLongSafety.addNonNegative(
                this.emittedItems.get(what), amount, "emitted item total");
    }

    @Inject(method = "addCrafting", at = @At("HEAD"))
    private void appliedenhancements$rejectUnsafeCraftCountSum(
            IPatternDetails details, long count, CallbackInfo callback) {
        long aggregatedCrafts = NativeCraftingLongSafety.addNonNegative(
                this.crafts.getOrDefault(details, 0L), count, "pattern craft total");
        for (var output : details.getOutputs()) {
            NativeCraftingLongSafety.multiplyNonNegative(
                    output.amount(), aggregatedCrafts, "aggregated pattern output total");
        }
    }

    /**
     * Validate the final task map once before it becomes an ICraftingPlan. This
     * covers sums of the same output key across different patterns without
     * maintaining side state that could diverge during branch rollback.
     */
    @Inject(method = "buildCraftingPlan", at = @At("HEAD"))
    private static void appliedenhancements$rejectUnsafeFinalPatternOutputs(
            CraftingSimulationState state,
            CraftingCalculation calculation,
            long calculatedAmount,
            CallbackInfoReturnable<CraftingPlan> callback) {
        var stateAccess = (CraftingSimulationStateLongSafetyAccessor) (Object) state;
        NativeCraftingLongSafety.validatePatternOutputs(
                stateAccess.appliedenhancements$getCrafts());
    }

    /**
     * AE2 merges a child state with unchecked subtraction/addition. Validate the
     * exact operands before applyDiff mutates the parent. Non-negative counter
     * validation also proves sizeDelta cannot be Long.MIN_VALUE, so AE2's later
     * -sizeDelta operations are representable.
     */
    @Inject(method = "applyDiff", at = @At("HEAD"))
    private void appliedenhancements$rejectUnsafeChildDiff(
            CraftingSimulationState parent, CallbackInfo callback) {
        var parentState = (CraftingSimulationStateLongSafetyAccessor) (Object) parent;
        KeyCounter parentUnmodified = parentState.appliedenhancements$getUnmodifiedCache();
        KeyCounter parentModifiable = parentState.appliedenhancements$getModifiableCache();

        for (var entry : this.requiredExtract) {
            AEKey key = entry.getKey();
            long parentDifference = appliedenhancements$checkedNonNegativeDifference(
                    parentUnmodified.get(key),
                    parentModifiable.get(key),
                    "parent simulated inventory difference");
            long childRequirement = NativeCraftingLongSafety.addNonNegative(
                    entry.getLongValue(), 0, "child required extraction");
            NativeCraftingLongSafety.addExact(
                    parentDifference, childRequirement, "required extraction diff");
        }

        for (var entry : this.modifiableCache) {
            appliedenhancements$checkedNonNegativeDifference(
                    entry.getLongValue(),
                    this.unmodifiedCache.get(entry.getKey()),
                    "child simulated inventory size delta");
        }
    }

    @Unique
    private static long appliedenhancements$checkedNonNegativeDifference(
            long left, long right, String operation) {
        NativeCraftingLongSafety.addNonNegative(left, 0, operation);
        NativeCraftingLongSafety.addNonNegative(right, 0, operation);
        return NativeCraftingLongSafety.addExact(left, -right, operation);
    }
}
