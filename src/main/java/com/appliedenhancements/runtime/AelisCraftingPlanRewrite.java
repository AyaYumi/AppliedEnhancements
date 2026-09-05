package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Keeps proven cyclic firings intact while allowing ordinary patterns to be batched. */
public final class AelisCraftingPlanRewrite {
    private AelisCraftingPlanRewrite() {
    }

    public static ICraftingPlan rewriteOrdinaryPatterns(
            ICraftingPlan plan, UnaryOperator<ICraftingPlan> rewrite) {
        var cycle = AelisCycleExecutionApi.getPlan(plan).orElse(null);
        if (cycle == null) {
            return rewrite.apply(plan);
        }
        var definitions = cycle.patternDefinitions();
        var ordinary = new LinkedHashMap<IPatternDetails, Long>();
        var cyclic = new LinkedHashMap<IPatternDetails, Long>();
        AelisCyclePatternNormalization.normalize(plan.patternTimes(), cycle).forEach((pattern, times) ->
                (definitions.contains(pattern.getDefinition()) ? cyclic : ordinary)
                        .put(pattern, times));
        var rewritten = rewrite.apply(copy(plan, ordinary));
        var combined = new LinkedHashMap<>(rewritten.patternTimes());
        cyclic.forEach((pattern, times) -> combined.merge(pattern, times, Math::addExact));
        return AelisCycleExecutionApi.copyMetadata(plan, copy(plan, combined));
    }

    private static CraftingPlan copy(ICraftingPlan plan, Map<IPatternDetails, Long> patterns) {
        return new CraftingPlan(plan.finalOutput(), plan.bytes(), plan.simulation(),
                plan.multiplePaths(), plan.usedItems(), plan.emittedItems(),
                plan.missingItems(), Map.copyOf(patterns));
    }
}
