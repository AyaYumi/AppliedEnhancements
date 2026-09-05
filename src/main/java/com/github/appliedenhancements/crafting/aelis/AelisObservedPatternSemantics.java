package com.github.appliedenhancements.crafting.aelis;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures the public, quantity-relevant behavior of an arbitrary pattern.
 *
 * <p>The snapshot deliberately does not depend on the pattern's Java class.
 * Unknown pattern implementations may therefore participate in the strict
 * quantity-feedback proof when their observable inputs, outputs, remainders
 * and external-push behavior are stable. The original pattern object is still
 * retained in the crafting plan, so machine routing metadata is unaffected.</p>
 */
final class AelisObservedPatternSemantics {
    private AelisObservedPatternSemantics() {
    }

    record InputSnapshot(long multiplier, List<GenericStack> possibleInputs,
            List<AEKey> remainingKeys) {
    }

    record Snapshot(AEItemKey definition, List<GenericStack> outputs,
            List<InputSnapshot> inputs, boolean pushesToExternalInventory) {
        boolean matches(IPatternDetails details) {
            Snapshot current = capture(details);
            return current != null && equals(current);
        }
    }

    /** Returns a snapshot only when two independent observations agree. */
    static Snapshot captureStable(IPatternDetails details) {
        Snapshot first = capture(details);
        if (first == null) {
            return null;
        }
        Snapshot second = capture(details);
        return first.equals(second) ? first : null;
    }

    private static Snapshot capture(IPatternDetails details) {
        if (details == null) {
            return null;
        }
        try {
            AEItemKey definition = details.getDefinition();
            List<GenericStack> liveOutputs = details.getOutputs();
            if (liveOutputs == null || liveOutputs.isEmpty()) {
                return null;
            }
            var outputs = new ArrayList<GenericStack>(liveOutputs.size());
            for (GenericStack output : liveOutputs) {
                if (!validStack(output)) {
                    return null;
                }
                outputs.add(output);
            }

            IPatternDetails.IInput[] liveInputs = details.getInputs();
            if (liveInputs == null || liveInputs.length == 0) {
                return null;
            }
            var inputs = new ArrayList<InputSnapshot>(liveInputs.length);
            for (IPatternDetails.IInput input : liveInputs) {
                if (input == null || input.getMultiplier() <= 0) {
                    return null;
                }
                GenericStack[] liveChoices = input.getPossibleInputs();
                if (liveChoices == null || liveChoices.length == 0) {
                    return null;
                }
                var choices = new ArrayList<GenericStack>(liveChoices.length);
                var remainders = new ArrayList<AEKey>(liveChoices.length);
                for (GenericStack choice : liveChoices) {
                    if (!validStack(choice)) {
                        return null;
                    }
                    choices.add(choice);
                    remainders.add(input.getRemainingKey(choice.what()));
                }
                inputs.add(new InputSnapshot(
                        input.getMultiplier(), List.copyOf(choices),
                        immutableNullableList(remainders)));
            }

            return new Snapshot(
                    definition, List.copyOf(outputs), List.copyOf(inputs),
                    details.supportsPushInputsToExternalInventory());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean validStack(GenericStack stack) {
        return stack != null && stack.what() != null && stack.amount() > 0;
    }

    private static <T> List<T> immutableNullableList(List<T> values) {
        // List.copyOf rejects null, while a null remaining key is the normal
        // representation of a consumable input.
        return java.util.Collections.unmodifiableList(new ArrayList<>(values));
    }
}
