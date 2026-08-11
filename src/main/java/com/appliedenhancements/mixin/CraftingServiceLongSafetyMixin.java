package com.appliedenhancements.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.service.CraftingService;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects unsafe third-party plans before a crafting CPU can mutate inventory. */
@Mixin(value = CraftingService.class, remap = false)
abstract class CraftingServiceLongSafetyMixin {
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$rejectUnsafeExternalPlan(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        try {
            NativeCraftingLongSafety.validatePlan(job);
        } catch (RuntimeException failure) {
            if (!NativeCraftingLongSafety.causedByUnsafeArithmetic(failure)) {
                throw failure;
            }
            callback.setReturnValue(CraftingSubmitResult.INCOMPLETE_PLAN);
        }
    }
}
