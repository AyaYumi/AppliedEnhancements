package com.appliedenhancements.mixin;

import appeng.client.gui.me.patternaccess.PatternSlot;
import com.appliedenhancements.integration.ae2.DuplicatePatternSourceScreenBridge;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds source-machine details to pattern tooltips only in duplicate-result views. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenPatternSourceTooltipMixin {
    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$appendPatternSource(
            ItemStack stack,
            CallbackInfoReturnable<List<Component>> callback) {
        if (!(hoveredSlot instanceof PatternSlot patternSlot)
                || !((Object) this instanceof DuplicatePatternSourceScreenBridge bridge)) {
            return;
        }
        List<Component> sourceTooltip = bridge.appliedenhancements$getPatternSourceTooltip(
                patternSlot);
        if (sourceTooltip.isEmpty()) {
            return;
        }
        var tooltip = new ArrayList<>(callback.getReturnValue());
        tooltip.addAll(sourceTooltip);
        callback.setReturnValue(List.copyOf(tooltip));
    }
}
