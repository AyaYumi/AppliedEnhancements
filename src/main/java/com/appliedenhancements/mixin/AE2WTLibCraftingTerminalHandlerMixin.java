package com.appliedenhancements.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes AE2WTLib (Wireless Terminals) compatibility by clamping negative accessible amounts.
 * Prevents integer overflow crashes when viewing extremely large or unlimited inventories
 * through wireless terminals.
 */
@Pseudo
@Mixin(targets = "de.mari_023.ae2wtlib.wct.CraftingTerminalHandler", remap = false)
public abstract class AE2WTLibCraftingTerminalHandlerMixin {
    @Inject(method = "getAccessibleAmount", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$saturateAccessibleAmount(ItemStack stack,
            CallbackInfoReturnable<Long> callback) {
        if (callback.getReturnValue() < 0) {
            callback.setReturnValue(Long.MAX_VALUE);
        }
    }
}
