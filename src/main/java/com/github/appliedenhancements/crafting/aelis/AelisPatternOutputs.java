package com.github.appliedenhancements.crafting.aelis;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.LinkedHashMap;
import java.util.List;

/** Exact output accounting shared by compilation and live candidate validation. */
final class AelisPatternOutputs {
    record Layout(long requestedOutput, List<GenericStack> secondaryOutputs) {
        Layout {
            secondaryOutputs = List.copyOf(secondaryOutputs);
        }
    }

    private AelisPatternOutputs() {}

    static Layout inspect(AEKey requestedKey, List<GenericStack> outputs) {
        if (requestedKey == null || outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("invalid_pattern_output");
        }
        long requestedOutput = 0;
        var secondary = new LinkedHashMap<AEKey, Long>();
        for (GenericStack output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0) {
                throw new IllegalArgumentException("invalid_pattern_output");
            }
            if (requestedKey.equals(output.what())) {
                requestedOutput = Math.addExact(requestedOutput, output.amount());
            } else {
                secondary.merge(output.what(), output.amount(), Math::addExact);
            }
        }
        if (requestedOutput == 0) {
            throw new IllegalArgumentException("secondary_or_fuzzy_output");
        }
        return new Layout(requestedOutput, secondary.entrySet().stream()
                .map(entry -> new GenericStack(entry.getKey(), entry.getValue())).toList());
    }
}
