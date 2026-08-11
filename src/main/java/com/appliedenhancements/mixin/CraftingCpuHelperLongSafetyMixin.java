package com.appliedenhancements.mixin;

import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ICraftingInventory;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingCpuHelper.class, remap = false)
abstract class CraftingCpuHelperLongSafetyMixin {
    /**
     * CraftingTreeNode calls this before its remaining-demand guard. AE2 uses
     * template.amount() * multiplier as the inventory extraction quantity, so
     * validate the product before a wrapped negative value reaches inventory.
     */
    @Inject(method = "extractTemplates", at = @At("HEAD"))
    private static void appliedenhancements$rejectUnsafeTemplateExtraction(
            ICraftingInventory inventory,
            InputTemplate template,
            long multiplier,
            CallbackInfoReturnable<Long> callback) {
        NativeCraftingLongSafety.requirePositive(
                template.amount(), "input template amount");
        NativeCraftingLongSafety.multiplyNonNegative(
                template.amount(), multiplier, "input template extraction total");
    }
}
