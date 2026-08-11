package com.appliedenhancements.mixin;

import appeng.client.gui.widgets.CPUSelectionList;
import appeng.core.localization.Tooltips;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.appliedenhancements.constants.InfiniteConstants;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Displays infinite storage and parallelism compactly in AE2 CPU selection screens.
 * Replaces Long.MAX_VALUE/Integer.MAX_VALUE with the 9.2E sentinel label.
 */
@Mixin(value = CPUSelectionList.class, remap = false)
public abstract class CPUSelectionListMixin {
    @Inject(method = "formatStorage", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$formatInfiniteStorage(
            CraftingStatusMenu.CraftingCpuListEntry cpu,
            CallbackInfoReturnable<String> callback) {
        if (cpu.storage() == InfiniteConstants.INFINITE_STORAGE) {
            callback.setReturnValue("9.2E");
        }
    }

    @WrapOperation(method = "drawBackgroundLayer", at = @At(value = "INVOKE",
            target = "Ljava/lang/String;valueOf(I)Ljava/lang/String;"))
    private String appliedenhancements$formatInfiniteParallelism(int value, Operation<String> original) {
        return value == InfiniteConstants.INFINITE_PARALLELISM
                ? "9.2E"
                : original.call(value);
    }

    @WrapOperation(method = "getTooltip", at = @At(value = "INVOKE",
            target = "Lappeng/core/localization/Tooltips;ofNumber(J)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent appliedenhancements$tooltipInfiniteParallelism(
            long value,
            Operation<MutableComponent> original) {
        return value == InfiniteConstants.INFINITE_PARALLELISM
                ? appliedenhancements$infiniteTooltipValue()
                : original.call(value);
    }

    @WrapOperation(method = "getTooltip", at = @At(value = "INVOKE",
            target = "Lappeng/core/localization/Tooltips;ofBytes(J)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent appliedenhancements$tooltipInfiniteStorage(
            long value,
            Operation<MutableComponent> original) {
        return value == InfiniteConstants.INFINITE_STORAGE
                ? appliedenhancements$infiniteTooltipValue()
                : original.call(value);
    }

    @Unique
    private static MutableComponent appliedenhancements$infiniteTooltipValue() {
        return Component.literal("9.2E")
                .withStyle(Tooltips.NUMBER_TEXT);
    }
}
