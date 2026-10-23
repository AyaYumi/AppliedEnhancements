package com.appliedenhancements.mixin;

import com.appliedenhancements.client.plan.AelisCraftConfirmScreen;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** EMI normally rejects slotless menus, including AE2's crafting confirmation menu. */
@Pseudo
@Mixin(targets = "dev.emi.emi.screen.EmiScreenBase", remap = false)
public abstract class EmiPlanScreenBaseMixin {
    @ModifyExpressionValue(method = "of", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/NonNullList;isEmpty()Z", remap = false), require = 0)
    private static boolean appliedenhancements$allowSlotlessPlanScreen(boolean empty, Screen screen) {
        // Keep EMI's own container bounds, initialization and rendering. Do not add fake slots.
        // Optional for EMI versions that change/remove this internal eligibility check.
        return empty && !(screen instanceof AelisCraftConfirmScreen);
    }
}
