package com.appliedenhancements.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.appliedenhancements.runtime.ExactCraftingTree;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.neuvillette.ae2ct.gui.CraftingTreeWidget", remap = false)
public abstract class Ae2CraftingTreeWidgetMixin {
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void appliedenhancements$attachExactTotals(AEBaseScreen<?> screen, @Coerce Object data,
            List<CraftingPlanSummaryEntry> entries, boolean missingOnly, CallbackInfo callback) {
        if (screen.getMenu() instanceof AelisCalculationPathMenuBridge bridge) {
            ExactCraftingTree.attach(data, bridge.appliedenhancements$getBigIntegerCraftAmounts(),
                    bridge.appliedenhancements$getBigIntegerMissingAmounts(),
                    bridge.appliedenhancements$getBigIntegerStoredAmounts(), bridge.appliedenhancements$getBigIntegerFinalAmount());
        }
    }

    @Inject(method = "getDrawAmount", at = @At("HEAD"), cancellable = true)
    private static void appliedenhancements$exactLabel(@Coerce Object node, CallbackInfoReturnable<String> callback) {
        String label = ExactCraftingTree.label(node);
        if (label != null) callback.setReturnValue(label);
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lappeng/client/gui/AEBaseScreen;drawTooltipWithHeader(Lnet/minecraft/client/gui/GuiGraphics;IILjava/util/List;)V"))
    private void appliedenhancements$exactTooltip(AEBaseScreen<?> screen, GuiGraphics graphics, int x, int y,
            List<Component> lines, Operation<Void> original) {
        original.call(screen, graphics, x, y,
                ExactCraftingTree.tooltip(this, x + screen.getGuiLeft(), y + screen.getGuiTop(), lines));
    }
}
