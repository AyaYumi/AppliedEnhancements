package com.appliedenhancements.mixin;

import com.appliedenhancements.client.plan.AelisCraftConfirmScreen;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Allow EMI in the slotless confirmation menu while keeping the fullscreen tree free of overlays. */
@Pseudo
@Mixin(targets = "dev.emi.emi.screen.EmiScreenBase", remap = false)
public abstract class EmiPlanScreenBaseMixin {
    @ModifyExpressionValue(method = "of", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/NonNullList;isEmpty()Z", remap = false), require = 0)
    private static boolean appliedenhancements$allowSlotlessPlanScreen(boolean empty, Screen screen) {
        // EMI's render and input paths both stop when the screen base is empty.
        // Rebuilding the screen on fullscreen exit runs EMI's init hook again.
        // Optional for EMI versions that change/remove this internal eligibility check.
        return screen instanceof AelisCraftConfirmScreen planScreen ? planScreen.isGraphFullscreen() : empty;
    }
}
