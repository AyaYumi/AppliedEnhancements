package com.appliedenhancements.mixin;

import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.me.crafting.CraftingStatusEntry;
import appeng.menu.me.crafting.CraftingStatus;
import com.appliedenhancements.runtime.ExactCraftingStatus;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenExactMixin {
    // UELM merges through a lambda and adds an external-pending constructor field.
    // Copy before native sorting so exact quantities also determine display order.
    @WrapOperation(method = "postUpdate", at = @At(value = "INVOKE",
            target = "Ljava/util/Collections;sort(Ljava/util/List;)V"))
    private void appliedenhancements$copyExact(java.util.List<CraftingStatusEntry> merged, Operation<Void> original,
            @Local(argsOnly = true) CraftingStatus incoming) {
        var updates = new java.util.HashMap<Long, CraftingStatusEntry>();
        for (var entry : incoming.getEntries()) {
            if (!entry.isDeleted()) updates.put(entry.getSerial(), entry);
        }
        for (var entry : merged) {
            var source = updates.get(entry.getSerial());
            if (source != null) ExactCraftingStatus.copy(source, entry);
        }
        original.call(merged);
    }
}
