package com.appliedenhancements.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.appliedenhancements.Config;
import com.appliedenhancements.util.SaturatingLongMath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Enhances crafting plan summary with more accurate material calculations.
 *
 * Shows:
 * - Missing items: items not available in storage
 * - Stored items: items taken from storage
 * - Crafting items: items that will be crafted
 */
@Mixin(value = CraftingPlanSummary.class, remap = false)
public abstract class CraftingPlanSummaryMixin {
    @Unique
    private static final int appliedenhancements$MISSING = 0;
    @Unique
    private static final int appliedenhancements$STORED = 1;
    @Unique
    private static final int appliedenhancements$CRAFTING = 2;

    @Inject(method = "fromJob", at = @At("RETURN"))
    private static void appliedenhancements$enhanceMaterialCalculation(
            IGrid grid, IActionSource actionSource, ICraftingPlan job,
            CallbackInfoReturnable<CraftingPlanSummary> callback) {
        if (!Config.ENABLE_ENHANCED_MATERIAL_CALCULATION.get()) {
            return;
        }

        // Build material plan
        var plan = new HashMap<AEKey, long[]>();

        // Items taken from storage
        for (var used : job.usedItems()) {
            var stats = appliedenhancements$getStats(plan, used.getKey());
            stats[appliedenhancements$STORED] = SaturatingLongMath.add(
                    stats[appliedenhancements$STORED], used.getLongValue());
        }

        // Missing items
        for (var missing : job.missingItems()) {
            var stats = appliedenhancements$getStats(plan, missing.getKey());
            stats[appliedenhancements$MISSING] = SaturatingLongMath.add(
                    stats[appliedenhancements$MISSING], missing.getLongValue());
        }

        // Items that will be emitted (crafted or produced)
        for (var emitted : job.emittedItems()) {
            var stats = appliedenhancements$getStats(plan, emitted.getKey());
            stats[appliedenhancements$STORED] = SaturatingLongMath.add(
                    stats[appliedenhancements$STORED], emitted.getLongValue());
            stats[appliedenhancements$CRAFTING] = SaturatingLongMath.add(
                    stats[appliedenhancements$CRAFTING], emitted.getLongValue());
        }

        // Calculate crafted amounts from pattern usage
        for (var pattern : job.patternTimes().entrySet()) {
            for (var output : pattern.getKey().getOutputs()) {
                var crafted = SaturatingLongMath.multiply(
                        output.amount(), pattern.getValue());
                var stats = appliedenhancements$getStats(plan, output.what());
                stats[appliedenhancements$CRAFTING] = SaturatingLongMath.add(
                        stats[appliedenhancements$CRAFTING], crafted);
            }
        }

        // Build sorted entry list
        var entries = new ArrayList<CraftingPlanSummaryEntry>(plan.size());
        for (var entry : plan.entrySet()) {
            var stats = entry.getValue();
            entries.add(new CraftingPlanSummaryEntry(
                    entry.getKey(),
                    stats[appliedenhancements$MISSING],
                    stats[appliedenhancements$STORED],
                    stats[appliedenhancements$CRAFTING]));
        }
        Collections.sort(entries);

        // Update the summary with enhanced entries
        ((CraftingPlanSummaryAccessor) (Object) callback.getReturnValue())
                .appliedenhancements$setEntries(List.copyOf(entries));
    }

    @Unique
    private static long[] appliedenhancements$getStats(HashMap<AEKey, long[]> plan, AEKey key) {
        return plan.computeIfAbsent(key, ignored -> new long[3]);
    }

}
