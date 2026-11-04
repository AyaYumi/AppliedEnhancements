package com.appliedenhancements.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores AE2's sorted pattern cache without changing candidate or provider ordering. */
@Mixin(targets = "appeng.me.service.helpers.NetworkCraftingProviders$PatternsForKey", remap = false)
public abstract class NetworkCraftingPatternCacheMixin {
    @Shadow
    private boolean needsSorting;

    @Inject(method = "sortPatterns", at = @At("RETURN"))
    private void appliedenhancements$cacheSortedPatterns(CallbackInfo callback) {
        // AE2 19.2.18 leaves this set after sorting. Every lookup then scans and
        // sorts all provider copies again, even when distinct() returns one pattern.
        // Mounting/unmounting patterns already marks the index dirty for us.
        needsSorting = false;
    }
}
