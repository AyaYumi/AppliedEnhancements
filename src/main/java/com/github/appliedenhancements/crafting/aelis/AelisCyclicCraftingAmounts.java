package com.github.appliedenhancements.crafting.aelis;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.appliedenhancements.util.SaturatingLongMath;
import java.util.Map;
import java.util.Objects;

/** Aggregates per-output item amounts produced by cyclic pattern firings. */
public final class AelisCyclicCraftingAmounts {
    private AelisCyclicCraftingAmounts() {
    }

    public static void addOutputs(
            Map<AEKey, Long> totals,
            Iterable<GenericStack> outputs,
            long patternTimes) {
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(outputs, "outputs");
        if (patternTimes <= 0) {
            return;
        }
        for (GenericStack output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0) {
                continue;
            }
            long produced = SaturatingLongMath.multiply(output.amount(), patternTimes);
            totals.merge(
                    output.what(), produced,
                    SaturatingLongMath::add);
        }
    }

    public static void merge(
            Map<AEKey, Long> totals,
            Map<AEKey, Long> addition) {
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(addition, "addition");
        for (var entry : addition.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && entry.getValue() > 0) {
                totals.merge(
                        entry.getKey(), entry.getValue(),
                        SaturatingLongMath::add);
            }
        }
    }
}
