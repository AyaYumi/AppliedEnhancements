package com.appliedenhancements.mixin;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.appliedenhancements.runtime.ExactCraftingStatus;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenExactMixin {
    @WrapOperation(method = "postUpdate", at = @At(value = "NEW", target = "appeng/menu/me/crafting/CraftingStatusEntry"))
    private CraftingStatusEntry appliedenhancements$copyExact(long serial, AEKey key, long stored, long active, long pending,
            Operation<CraftingStatusEntry> original, @Local(name = "entry") CraftingStatusEntry incoming) {
        return ExactCraftingStatus.copy(incoming, original.call(serial, key, stored, active, pending));
    }
}
