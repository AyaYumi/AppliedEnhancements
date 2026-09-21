package com.appliedenhancements.mixin;

import appeng.client.gui.me.crafting.CraftingStatusTableRenderer;
import appeng.menu.me.crafting.CraftingStatusEntry;
import appeng.core.localization.GuiText;
import com.appliedenhancements.ae2.ExactCraftingStatusEntry;
import com.appliedenhancements.util.AmountFormatter;
import java.math.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingStatusTableRenderer.class, remap = false)
public abstract class CraftingStatusTableRendererExactMixin {
    /** Match the last-batch amount displayed by ExactCraftingStatusLabels. */
    @Unique
    private static final int ACTIVE_BACKGROUND_COLOR = 0x8052A8FF;

    @Inject(method = "getEntryBackgroundColor(Lappeng/menu/me/crafting/CraftingStatusEntry;)I", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$activeBackground(
            CraftingStatusEntry entry, CallbackInfoReturnable<Integer> ci) {
        var lastBatch = ((ExactCraftingStatusEntry) entry).appliedenhancements$getLastBatch();
        if (lastBatch != null
                || com.appliedenhancements.runtime.ExactCraftingStatus.active(entry).signum() > 0) {
            ci.setReturnValue(ACTIVE_BACKGROUND_COLOR);
        }
    }

    @Inject(method = "getEntryDescription", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$pendingLabel(CraftingStatusEntry entry, CallbackInfoReturnable<List<Component>> ci) {
        ci.setReturnValue(com.appliedenhancements.runtime.ExactCraftingStatusLabels.replace(entry, ci.getReturnValue(), false));
    }
    @Inject(method = "getEntryTooltip", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$pendingTooltip(CraftingStatusEntry entry, CallbackInfoReturnable<List<Component>> ci) {
        ci.setReturnValue(com.appliedenhancements.runtime.ExactCraftingStatusLabels.replace(entry, ci.getReturnValue(), true));
    }
}
