package com.appliedenhancements.mixin;

import appeng.client.gui.widgets.CPUSelectionList;
import appeng.client.gui.widgets.InfoBar;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.appliedenhancements.constants.InfiniteConstants;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Formats the sentinel after compatible addons have compacted ordinary CPU counts. */
@Mixin(value = CPUSelectionList.class, priority = 900, remap = false)
public abstract class CPUSelectionListFinalAmountsMixin {
    // Use the final text call shared by upstream AE2 and its UELM fork.
    @WrapOperation(method = "drawBackgroundLayer", at = @At(value = "INVOKE",
            target = "Lappeng/client/gui/widgets/InfoBar;add(Ljava/lang/String;IF)V", ordinal = 1))
    private void appliedenhancements$formatFinalStorage(InfoBar bar, String text, int color,
            float scale, Operation<Void> original,
            @Local(name = "cpu") CraftingStatusMenu.CraftingCpuListEntry cpu) {
        if (cpu.storage() == InfiniteConstants.INFINITE_STORAGE) text = "9.2E";
        original.call(bar, text, color, scale);
    }

    @WrapOperation(method = "drawBackgroundLayer", at = @At(value = "INVOKE",
            target = "Lappeng/client/gui/widgets/InfoBar;add(Ljava/lang/String;IF)V", ordinal = 2))
    private void appliedenhancements$formatFinalParallelism(InfoBar bar, String text, int color,
            float scale, Operation<Void> original,
            @Local(name = "cpu") CraftingStatusMenu.CraftingCpuListEntry cpu) {
        if (cpu.coProcessors() == InfiniteConstants.INFINITE_PARALLELISM) text = "9.2E";
        original.call(bar, text, color, scale);
    }
}
