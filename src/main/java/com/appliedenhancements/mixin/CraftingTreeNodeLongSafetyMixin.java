package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingTreeNode.class, remap = false)
abstract class CraftingTreeNodeLongSafetyMixin {
    @Shadow
    @Final
    private long amount;

    @Shadow
    @Final
    @Nullable
    IPatternDetails.IInput parentInput;

    /**
     * AE2 multiplies this node amount by the (possibly long-range) request for
     * both emitted items and pattern demand. Check the remaining request after
     * inventory extraction, immediately before either unchecked multiplication.
     */
    @Inject(method = "request", at = @At(value = "FIELD",
            target = "Lappeng/crafting/CraftingTreeNode;canEmit:Z",
            opcode = Opcodes.GETFIELD))
    private void appliedenhancements$rejectUnsafeNodeProduct(
            CraftingSimulationState inventory,
            long requestedAmount,
            @Nullable KeyCounter containerItems,
            CallbackInfo callback) {
        NativeCraftingLongSafety.multiplyNonNegative(
                this.amount, requestedAmount, "crafting tree node demand");
    }

    /** Guard accumulation when several inputs return the same container key. */
    @Inject(method = "addContainerItems", at = @At("HEAD"))
    private void appliedenhancements$rejectUnsafeContainerItemSum(
            AEKey template,
            long multiplier,
            @Nullable KeyCounter outputList,
            CallbackInfo callback) {
        if (outputList == null || this.parentInput == null) {
            return;
        }

        AEKey containerItem = this.parentInput.getRemainingKey(template);
        if (containerItem != null) {
            NativeCraftingLongSafety.addNonNegative(
                    outputList.get(containerItem), multiplier, "container item total");
        }
    }
}
