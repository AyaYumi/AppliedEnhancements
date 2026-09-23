package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingTreeProcess.class, remap = false)
abstract class CraftingTreeProcessLongSafetyMixin {
    @Shadow
    @Final
    IPatternDetails details;

    /**
     * Validate output multiplication before AE2 inserts an output. Input demand
     * multiplication is left to AE2. A non-positive times value catches overflow in
     * AE2's native ceil-division expression before it can mutate state.
     */
    @Inject(method = "request", at = @At("HEAD"))
    private void appliedenhancements$rejectUnsafePatternProducts(
            CraftingSimulationState inventory, long times, CallbackInfo callback) {
        if (!CraftingPlannerIntervention.enabled()) return;
        NativeCraftingLongSafety.requirePositive(times, "pattern craft count");
        for (var output : this.details.getOutputs()) {
            NativeCraftingLongSafety.multiplyNonNegative(
                    output.amount(), times, "pattern output amount");
        }
    }

    /** AE2 normally sums matching outputs with unchecked {@code long} addition. */
    @Inject(method = "getOutputCount", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$sumPatternOutputsExactly(
            AEKey what, CallbackInfoReturnable<Long> callback) {
        if (!CraftingPlannerIntervention.enabled()) return;
        long total = 0;
        for (var output : this.details.getOutputs()) {
            if (what.matches(output)) {
                total = NativeCraftingLongSafety.addNonNegative(
                        total, output.amount(), "matching pattern output total");
            }
        }
        callback.setReturnValue(NativeCraftingLongSafety.requirePositive(
                total, "matching pattern output count"));
    }
}
