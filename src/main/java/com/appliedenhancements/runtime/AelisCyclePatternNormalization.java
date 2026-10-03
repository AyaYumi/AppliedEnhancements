package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Restores cyclic pattern identities and firing counts after a quantity-only rewrite. */
public final class AelisCyclePatternNormalization {
    private AelisCyclePatternNormalization() {}

    public static Map<IPatternDetails, Long> normalize(
            Map<IPatternDetails, Long> tasks, AelisCycleExecutionPlan cycle) {
        if (cycle == null) return tasks;
        try { return normalizeTasks(tasks, cycle); }
        catch (RuntimeException | LinkageError unavailable) { return tasks; }
    }

    private static Map<IPatternDetails, Long> normalizeTasks(
            Map<IPatternDetails, Long> tasks, AelisCycleExecutionPlan cycle) {
        var definitions = cycle.patternDefinitions();
        var normalized = new LinkedHashMap<IPatternDetails, Long>();
        boolean changed = false;
        for (var entry : tasks.entrySet()) {
            IPatternDetails original = entry.getKey();
            long multiplier = 1;
            var visited = new IdentityHashMap<IPatternDetails, Boolean>();
            SmartDoublingPatternAccess.Scale scaled;
            while ((scaled = SmartDoublingPatternAccess.resolve(original)) != null) {
                if (visited.put(original, Boolean.TRUE) != null) {
                    return tasks;
                }
                long operations = scaled.multiplier();
                if (operations <= 0 || multiplier > Long.MAX_VALUE / operations || scaled.original() == null) return tasks;
                multiplier *= operations;
                original = scaled.original();
            }
            if (original != entry.getKey() && definitions.contains(original.getDefinition())) {
                normalized.merge(original, Math.multiplyExact(entry.getValue(), multiplier), Math::addExact);
                changed = true;
            } else {
                normalized.merge(entry.getKey(), entry.getValue(), Math::addExact);
            }
        }
        return changed ? Map.copyOf(normalized) : tasks;
    }
}
