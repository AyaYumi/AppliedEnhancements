package com.appliedenhancements.mixin;

import com.appliedenhancements.integration.ae2.PatternQuickMoveScreenBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenQuickMoveMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$handleQuickMoveClick(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof PatternQuickMoveScreenBridge bridge
                && bridge.appliedenhancements$quickMoveMouseClicked(
                        mouseX, mouseY, button)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$handleQuickMoveDrag(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY,
            CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof PatternQuickMoveScreenBridge bridge
                && bridge.appliedenhancements$quickMoveMouseDragged(
                        mouseX, mouseY, button, dragX, dragY)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$handleQuickMoveRelease(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof PatternQuickMoveScreenBridge bridge
                && bridge.appliedenhancements$quickMoveMouseReleased(
                        mouseX, mouseY, button)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void appliedenhancements$renderQuickMoveOverlay(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo callback) {
        if ((Object) this instanceof PatternQuickMoveScreenBridge bridge) {
            bridge.appliedenhancements$renderQuickMoveOverlay(
                    graphics, mouseX, mouseY, partialTick);
        }
    }
}
