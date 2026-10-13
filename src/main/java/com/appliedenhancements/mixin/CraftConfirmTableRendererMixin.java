package com.appliedenhancements.mixin;

import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import appeng.core.localization.GuiText;
import com.appliedenhancements.client.CraftingPlanAmountFormatter;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import java.math.BigInteger;
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
        var lines = new ArrayList<>(callback.getReturnValue());
        appliedenhancements$replaceCraftAmount(entry, lines, AmountFormat.SLOT);
        BigInteger amount = appliedenhancements$getCyclicCraftAmount(entry);
        if (amount.signum() <= 0) {
            callback.setReturnValue(List.copyOf(lines));
            return;
        }
        lines.add(Component.translatable(
                CYCLIC_CRAFT_AMOUNT_KEY,
                amount.bitLength() <= 63 ? entry.getWhat().formatAmount(amount.longValueExact(), AmountFormat.SLOT)
                        : appliedenhancements$formatExactAmount(entry, amount, AmountFormat.SLOT)));
        callback.setReturnValue(List.copyOf(lines));
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$appendCyclicCraftAmountTooltip(
            CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> callback) {
        var lines = new ArrayList<>(callback.getReturnValue());
        appliedenhancements$replaceCraftAmount(entry, lines, AmountFormat.FULL);
        BigInteger amount = appliedenhancements$getCyclicCraftAmount(entry);
        if (amount.signum() <= 0) {
            callback.setReturnValue(List.copyOf(lines));
            return;
        }
        lines.add(Component.translatable(
                CYCLIC_CRAFT_AMOUNT_KEY,
                amount.bitLength() <= 63 ? entry.getWhat().formatAmount(amount.longValueExact(), AmountFormat.FULL)
                        : appliedenhancements$formatExactAmount(entry, amount, AmountFormat.FULL)));
        callback.setReturnValue(List.copyOf(lines));
    }

    @Unique
    private BigInteger appliedenhancements$getCyclicCraftAmount(
            CraftingPlanSummaryEntry entry) {
        var screen = ((AbstractTableRendererAccessor) (Object) this)
                .appliedenhancements$getScreen();
        if (!(screen.getMenu() instanceof AelisCalculationPathMenuBridge bridge)) {
            return BigInteger.ZERO;
        }
        return bridge.appliedenhancements$getBigIntegerCyclicCraftAmounts()
                .getOrDefault(entry.getWhat(), BigInteger.ZERO);
    }

    @Unique
    private void appliedenhancements$replaceCraftAmount(
            CraftingPlanSummaryEntry entry, ArrayList<Component> lines,
            AmountFormat format) {
        var screen = ((AbstractTableRendererAccessor) (Object) this)
                .appliedenhancements$getScreen();
        if (!(screen.getMenu() instanceof AelisCalculationPathMenuBridge bridge)) {
            return;
        }
        appliedenhancements$replaceExactAmounts(entry, lines, format, bridge);
    }

    @Unique
    private static void appliedenhancements$replaceExactAmounts(CraftingPlanSummaryEntry entry,
            ArrayList<Component> lines, AmountFormat format, AelisCalculationPathMenuBridge bridge) {
        var exact = bridge.appliedenhancements$getBigIntegerCraftAmounts()
                .get(entry.getWhat());
        var exactMissing = bridge.appliedenhancements$getBigIntegerMissingAmounts().get(entry.getWhat());
        var exactStored = bridge.appliedenhancements$getBigIntegerStoredAmounts().get(entry.getWhat());
        int storedLine = lines.size() - 1 - (entry.getCraftAmount() > 0 ? 1 : 0)
                - (entry.getMissingAmount() > 0 ? 1 : 0);
        if (exactStored != null && entry.getStoredAmount() > 0 && storedLine >= 0) {
            lines.set(storedLine, GuiText.FromStorage.text(
                    appliedenhancements$formatExactAmount(entry, exactStored, format)));
        }
        int missingLine = lines.size() - 1 - (entry.getCraftAmount() > 0 ? 1 : 0);
        if (exactMissing != null && entry.getMissingAmount() > 0 && missingLine >= 0) {
            lines.set(missingLine, GuiText.Missing.text(
                    appliedenhancements$formatExactAmount(entry, exactMissing, format)));
        }
        if (exact == null || exact.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) <= 0
                || entry.getCraftAmount() <= 0 || lines.isEmpty()) {
            return;
        }
        lines.set(lines.size() - 1, GuiText.ToCraft.text(
                appliedenhancements$formatExactAmount(entry, exact, format)));
    }

    @Unique
    private static String appliedenhancements$formatExactAmount(
            CraftingPlanSummaryEntry entry, BigInteger amount,
            AmountFormat format) {
        return CraftingPlanAmountFormatter.format(entry.getWhat(), amount, format);
    }
}
