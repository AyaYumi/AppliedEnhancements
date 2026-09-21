package com.appliedenhancements.runtime;

import appeng.menu.me.crafting.CraftingStatusEntry;
import appeng.core.localization.GuiText;
import com.appliedenhancements.ae2.ExactCraftingStatusEntry;
import com.appliedenhancements.util.AmountFormatter;
import java.math.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Shared by list cells and hover text so both show the same exact quantities. */
public final class ExactCraftingStatusLabels {
    private ExactCraftingStatusLabels() { }
    public static List<Component> replace(CraftingStatusEntry entry, List<Component> lines, boolean full) {
        var exact = ((ExactCraftingStatusEntry) entry).appliedenhancements$getPending();
        var active = ((ExactCraftingStatusEntry) entry).appliedenhancements$getActive();
        var stored = ((ExactCraftingStatusEntry) entry).appliedenhancements$getStored();
        var batch = ((ExactCraftingStatusEntry) entry).appliedenhancements$getLastBatch();
        if ((exact == null && active == null && stored == null && batch == null) || entry.getWhat() == null) return lines;
        if (batch != null) active = batch.outputAmount();
        if (exact == null) exact = BigInteger.valueOf(entry.getPendingAmount());
        if (active == null) active = BigInteger.valueOf(entry.getActiveAmount());
        if (stored == null) stored = BigInteger.valueOf(entry.getStoredAmount());
        var key = entry.getWhat();
        String amount = full ? AmountFormatter.formatFull(exact, key.getAmountPerUnit())
                : AmountFormatter.format(new BigDecimal(exact).divide(BigDecimal.valueOf(key.getAmountPerUnit()), MathContext.DECIMAL128));
        if (full && key.getUnitSymbol() != null) amount += " " + key.getUnitSymbol();
        String activeAmount = full ? AmountFormatter.formatFull(active, key.getAmountPerUnit())
                : AmountFormatter.format(new BigDecimal(active).divide(BigDecimal.valueOf(key.getAmountPerUnit()), MathContext.DECIMAL128));
        if (full && key.getUnitSymbol() != null) activeAmount += " " + key.getUnitSymbol();
        String storedAmount = full ? AmountFormatter.formatFull(stored, key.getAmountPerUnit())
                : AmountFormatter.format(new BigDecimal(stored).divide(BigDecimal.valueOf(key.getAmountPerUnit()), MathContext.DECIMAL128));
        if (full && key.getUnitSymbol() != null) storedAmount += " " + key.getUnitSymbol();
        var storedKey = ((TranslatableContents) GuiText.FromStorage.text("").getContents()).getKey();
        var scheduledKey = ((TranslatableContents) GuiText.Scheduled.text("").getContents()).getKey();
        var craftingKey = ((TranslatableContents) GuiText.Crafting.text("").getContents()).getKey();
        var result = new ArrayList<Component>(lines);
        int scheduledIndex = -1;
        boolean hasCraftingLine = false;
        boolean hasStoredLine = false;
        for (int i = 0; i < result.size(); i++) {
            if (!(result.get(i).getContents() instanceof TranslatableContents text)) {
                continue;
            }
            if (text.getKey().equals(storedKey)) {
                hasStoredLine = true;
                result.set(i, GuiText.FromStorage.text(storedAmount).setStyle(result.get(i).getStyle()));
            } else if (text.getKey().equals(craftingKey)) {
                hasCraftingLine = true;
                result.set(i, GuiText.Crafting.text(activeAmount).setStyle(result.get(i).getStyle()));
            } else if (text.getKey().equals(scheduledKey)) {
                scheduledIndex = i;
                result.set(i, GuiText.Scheduled.text(amount)
                        .setStyle(result.get(i).getStyle()));
            }
        }
        if (exact.signum() > 0 && scheduledIndex < 0) {
            scheduledIndex = result.size();
            result.add(GuiText.Scheduled.text(amount));
        }
        if ((exact.signum() > 0 || active.signum() > 0) && !hasCraftingLine) {
            result.add(scheduledIndex < 0 ? result.size() : scheduledIndex,
                    GuiText.Crafting.text(activeAmount));
        }
        if (stored.signum() > 0 && !hasStoredLine) result.add(0, GuiText.FromStorage.text(storedAmount));
        if (full && batch != null) {
            result.add(Component.translatable("gui.appliedenhancements.crafting.last_batch"));
            for (var input : batch.inputs().entrySet()) {
                var inputKey = input.getKey();
                String inputAmount = AmountFormatter.formatFull(input.getValue(), inputKey.getAmountPerUnit());
                if (inputKey.getUnitSymbol() != null) inputAmount += " " + inputKey.getUnitSymbol();
                result.add(Component.translatable("gui.appliedenhancements.crafting.batch_input", inputKey.getDisplayName(), inputAmount));
            }
        }
        return result;
    }
}
