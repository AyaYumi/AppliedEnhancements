package com.appliedenhancements.mixin;

import com.appliedenhancements.Config;
import com.appliedenhancements.runtime.ExactCraftingTree;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.neuvillette.ae2ct.api.CraftingTreeHelper", remap = false)
public abstract class Ae2CraftingTreeHelperMixin {
    @Inject(method = "build", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$buildExactTree(boolean missingOnly, CallbackInfoReturnable<Object> callback) {
        if (Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()) callback.setReturnValue(ExactCraftingTree.build(this, missingOnly));
    }
}
