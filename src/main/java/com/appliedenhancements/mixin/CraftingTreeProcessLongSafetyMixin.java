package com.appliedenhancements.mixin;

import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
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
    private Map<CraftingTreeNode, Long> nodes;

    @Shadow
    @Final
    IPatternDetails details;

    /**
     * Validate every input/output multiplication before AE2 requests an input
     * or inserts an output. A non-positive times value also catches overflow in
     * AE2's native ceil-division expression before it can mutate state.
     */
    @Inject(method = "request", at = @At("HEAD"))
    private void appliedenhancements$rejectUnsafePatternProducts(
            CraftingSimulationState inventory, long times, CallbackInfo callback) {
        NativeCraftingLongSafety.requirePositive(times, "pattern craft count");
        for (long multiplier : this.nodes.values()) {
            NativeCraftingLongSafety.multiplyNonNegative(
                    multiplier, times, "pattern input demand");
        }
        for (var output : this.details.getOutputs()) {
            NativeCraftingLongSafety.multiplyNonNegative(
                    output.amount(), times, "pattern output amount");
        }
    }

    /** AE2 normally sums matching outputs with unchecked {@code long} addition. */
    @Inject(method = "getOutputCount", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$sumPatternOutputsExactly(
            AEKey what, CallbackInfoReturnable<Long> callback) {
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
