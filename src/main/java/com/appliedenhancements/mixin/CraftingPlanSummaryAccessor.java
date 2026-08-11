package com.appliedenhancements.mixin;

import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Accessor for CraftingPlanSummary to allow updating the entry list.
 * Used for displaying accurate material calculations.
 */
@Mixin(value = CraftingPlanSummary.class, remap = false)
public interface CraftingPlanSummaryAccessor {
    @Mutable
    @Accessor("entries")
    void appliedenhancements$setEntries(List<CraftingPlanSummaryEntry> entries);
}
