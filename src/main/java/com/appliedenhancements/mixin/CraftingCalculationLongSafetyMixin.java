package com.appliedenhancements.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCalculation.class, remap = false)
abstract class CraftingCalculationLongSafetyMixin {
    @Shadow
    @Final
    private KeyCounter missing;

    @Inject(method = "addMissing", at = @At("HEAD"))
    private void appliedenhancements$rejectUnsafeMissingItemSum(
            AEKey what, long amount, CallbackInfo callback) {
        NativeCraftingLongSafety.addNonNegative(
                this.missing.get(what), amount, "missing item total");
    }
}
