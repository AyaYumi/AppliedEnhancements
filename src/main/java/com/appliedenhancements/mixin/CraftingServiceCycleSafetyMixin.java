package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.CraftingService;
import com.appliedenhancements.runtime.AelisCycleDispatchScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CraftingService.class, remap = false, priority = 1100)
public abstract class CraftingServiceCycleSafetyMixin {
    @ModifyReturnValue(method = "getProviders", at = @At("RETURN"))
    private Iterable<ICraftingProvider> appliedenhancements$batchActiveCycle(
            Iterable<ICraftingProvider> providers, IPatternDetails pattern) {
        return AelisCycleDispatchScope.providers(pattern, providers);
    }

}
