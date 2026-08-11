package com.github.appliedenhancements.crafting.maxfast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime proof for a statically safe quantity-feedback pattern whose deeper
 * descendant isolation could not be established. If every direct non-feedback
 * input is already present, none of those descendants can execute, so batching
 * retains AE2's per-pattern inventory semantics.
 */
final class OmniQuantityFeedbackRuntimeBatch {
    private OmniQuantityFeedbackRuntimeBatch() {
    }

    record Input<K>(K key, long amount, long multiplier, boolean feedback) {
    }

    record Plan<K>(long remainingItems, long patternTimes, long feedbackItems,
            Map<K, Long> nonFeedbackRequirements) {
        Plan {
            nonFeedbackRequirements = Map.copyOf(nonFeedbackRequirements);
        }
    }

    @FunctionalInterface
    interface Availability<K> {
        long available(K key, long requestedAmount);
    }

    static <K> Optional<Plan<K>> plan(long nodeAmount,
            long remainingMultipliers,
            long outputPerPattern, List<Input<K>> inputs) {
        if (nodeAmount <= 0 || remainingMultipliers <= 0
                || outputPerPattern <= 0
                || inputs == null || inputs.isEmpty()) {
            return Optional.empty();
        }

        try {
            long remainingItems = Math.multiplyExact(
                    nodeAmount, remainingMultipliers);
            long patternTimes = remainingItems / outputPerPattern
                    + (remainingItems % outputPerPattern == 0 ? 0 : 1);

            int feedbackInputs = 0;
            long feedbackItems = 0;
            var requirements = new LinkedHashMap<K, Long>();
            for (Input<K> input : inputs) {
                if (input == null || input.key == null
                        || input.amount <= 0 || input.multiplier <= 0) {
                    return Optional.empty();
                }
                long itemsPerPattern = Math.multiplyExact(
                        input.amount, input.multiplier);
                long requiredItems = Math.multiplyExact(
                        itemsPerPattern, patternTimes);
                if (input.feedback) {
                    feedbackInputs++;
                    feedbackItems = requiredItems;
                } else {
                    requirements.merge(
                            input.key, requiredItems, Math::addExact);
                }
            }
            if (feedbackInputs != 1) {
                return Optional.empty();
            }
            return Optional.of(new Plan<>(
                    remainingItems, patternTimes, feedbackItems, requirements));
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

    static <K> boolean allNonFeedbackInputsAvailable(
            Plan<K> plan, Availability<K> availability) {
        if (plan == null || availability == null) {
            return false;
        }
        for (var requirement : plan.nonFeedbackRequirements.entrySet()) {
            long required = requirement.getValue();
            if (required < 0
                    || availability.available(requirement.getKey(), required) < required) {
                return false;
            }
        }
        return true;
    }
}
