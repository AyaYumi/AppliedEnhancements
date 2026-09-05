package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.github.appliedenhancements.integration.ae2.AelisScaledPattern;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Restores cyclic pattern identities and firing counts after a quantity-only rewrite. */
public final class AelisCyclePatternNormalization {
    private AelisCyclePatternNormalization() {}

    public static Map<IPatternDetails, Long> normalize(
            Map<IPatternDetails, Long> tasks, AelisCycleExecutionPlan cycle) {
        if (cycle == null) return tasks;
        var definitions = cycle.patternDefinitions();
        var normalized = new LinkedHashMap<IPatternDetails, Long>();
        boolean changed = false;
        for (var entry : tasks.entrySet()) {
            IPatternDetails original = entry.getKey();
            long multiplier = 1;
            var visited = new IdentityHashMap<IPatternDetails, Boolean>();
            while (original instanceof AelisScaledPattern scaled) {
                if (visited.put(original, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException("Recursive scaled-pattern wrapper");
                }
                long operations = scaled.appliedenhancements$operationsPerPush();
                if (operations <= 0) throw new IllegalArgumentException("Invalid scaled-pattern multiplier");
                multiplier = Math.multiplyExact(multiplier, operations);
                original = Objects.requireNonNull(scaled.appliedenhancements$originalPattern());
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
