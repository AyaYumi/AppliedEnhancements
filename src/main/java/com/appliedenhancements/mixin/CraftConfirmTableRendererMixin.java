package com.appliedenhancements.mixin;

import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftConfirmTableRenderer.class, remap = false)
public abstract class CraftConfirmTableRendererMixin {
    @Unique
    private static final String CYCLIC_CRAFT_AMOUNT_KEY =
            "gui.appliedenhancements.calculation_result.cyclic_crafting_amount";

    @Inject(method = "getEntryDescription", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$appendCyclicCraftAmount(
            CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> callback) {
        long amount = appliedenhancements$getCyclicCraftAmount(entry);
        if (amount <= 0) {
            return;
        }
        var lines = new ArrayList<>(callback.getReturnValue());
        lines.add(Component.translatable(
                CYCLIC_CRAFT_AMOUNT_KEY,
                entry.getWhat().formatAmount(amount, AmountFormat.SLOT)));
        callback.setReturnValue(List.copyOf(lines));
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$appendCyclicCraftAmountTooltip(
            CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> callback) {
        long amount = appliedenhancements$getCyclicCraftAmount(entry);
        if (amount <= 0) {
            return;
        }
        var lines = new ArrayList<>(callback.getReturnValue());
        lines.add(Component.translatable(
                CYCLIC_CRAFT_AMOUNT_KEY,
                entry.getWhat().formatAmount(amount, AmountFormat.FULL)));
        callback.setReturnValue(List.copyOf(lines));
    }

    @Unique
    private long appliedenhancements$getCyclicCraftAmount(
            CraftingPlanSummaryEntry entry) {
        var screen = ((AbstractTableRendererAccessor) (Object) this)
                .appliedenhancements$getScreen();
        if (!(screen.getMenu() instanceof AelisCalculationPathMenuBridge bridge)) {
            return 0;
        }
        return bridge.appliedenhancements$getCyclicCraftAmounts()
                .getOrDefault(entry.getWhat(), 0L);
    }
}
