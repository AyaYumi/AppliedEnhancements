package com.appliedenhancements.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.service.CraftingService;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import com.appliedenhancements.runtime.AelisCycleDispatchScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingService.class, remap = false, priority = 1100)
public abstract class CraftingServiceCycleSafetyMixin {
    @ModifyReturnValue(method = "getProviders", at = @At("RETURN"))
    private Iterable<ICraftingProvider> appliedenhancements$batchActiveCycle(
            Iterable<ICraftingProvider> providers, IPatternDetails pattern) {
        return AelisCycleDispatchScope.providers(pattern, providers);
    }

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$rejectUnsupportedCycleCpu(
            ICraftingPlan plan,
            ICraftingRequester requester,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (target != null
                && AelisCycleExecutionApi.requiresCycleAwareCpu(plan)
                && !AelisCycleExecutionApi.supports(target)) {
            callback.setReturnValue(CraftingSubmitResult.noSuitableCpu(
                    new UnsuitableCpus(0, 0, 0, 1)));
        }
    }
}
