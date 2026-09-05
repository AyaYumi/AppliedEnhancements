package com.github.appliedenhancements.crafting.aelis;

import com.appliedenhancements.api.AelisCycleSeedPolicy;
import java.util.List;

/**
 * Proof policy for batching a quantity-limited pattern whose primary output is
 * also one of its consumable inputs.
 *
 * <p>The planner treats stored target items as the startup seed and each firing
 * as a net gain of {@code output - feedbackInput}. Only a real seed shortage is
 * reported as missing; later feedback inputs are supplied by earlier firings.
 * This is valid only when the feedback occurrence is recursion-filtered and the
 * remaining descendants cannot produce the feedback key.</p>
 */
final class AelisQuantityFeedbackBatch {
    private AelisQuantityFeedbackBatch() {
    }

    record InputShape(boolean selfInput, boolean exact, boolean consumable,
            boolean remainderFree, long amount, long multiplier) {
    }

    record Shape(long producingNodeAmount, int candidateCount,
            boolean hasContainerItems, boolean deterministicPattern,
            boolean primaryOutputOnly, boolean feedbackOccurrenceTerminal,
            int outputEntryCount, long outputPerPattern,
            List<InputShape> inputs) {
        Shape {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }

    record Profile(long outputPerPattern, long selfItemsPerPattern,
            long netOutputPerPattern) {
    }

    record Plan(long patternTimes, long missingSeedItems,
            long initialItems, long netOutputPerPattern,
            long surplusItems) {
    }

    record DescendantNodeShape(boolean producesFeedbackKey,
            boolean terminalOrEmitter, boolean nativeBoundary,
            boolean hasReusableInputs, boolean allCandidatesCompiled,
            int compiledCandidateCount, int candidateCount) {
    }

    record DescendantCandidateShape(boolean hasContainerItems,
            boolean limitsQuantity, boolean safeQuantityFeedbackBatch) {
    }

    record DescendantInputShape(boolean reusable, boolean substitute,
            boolean producesFeedbackKey) {
    }

    static boolean isSafe(Shape shape) {
        if (shape == null
                || shape.producingNodeAmount != 1
                || shape.candidateCount != 1
                || shape.hasContainerItems
                || !shape.deterministicPattern
                || !shape.primaryOutputOnly
                || !shape.feedbackOccurrenceTerminal
                || shape.outputEntryCount != 1
                || shape.outputPerPattern <= 0
                || shape.inputs.isEmpty()) {
            return false;
        }

        int selfInputs = 0;
        long selfItemsPerPattern = 0;
        try {
            for (InputShape input : shape.inputs) {
                if (input == null
                        || !input.exact
                        || !input.consumable
                        || !input.remainderFree
                        || input.amount <= 0
                        || input.multiplier <= 0) {
                    return false;
                }
                long itemsPerPattern = Math.multiplyExact(
                        input.amount, input.multiplier);
                if (input.selfInput) {
                    selfInputs++;
                    selfItemsPerPattern = itemsPerPattern;
                }
            }
        } catch (ArithmeticException overflow) {
            return false;
        }

        // A unique, net-positive feedback input is the only cyclic quantity
        // relationship covered by the proof above.
        return selfInputs == 1
                && shape.outputPerPattern > selfItemsPerPattern;
    }

    static Profile profile(Shape shape) {
        if (!isSafe(shape)) {
            return null;
        }
        try {
            for (InputShape input : shape.inputs) {
                if (input.selfInput) {
                    long selfItems = Math.multiplyExact(
                            input.amount, input.multiplier);
                    return new Profile(
                            shape.outputPerPattern,
                            selfItems,
                            Math.subtractExact(
                                    shape.outputPerPattern, selfItems));
                }
            }
        } catch (ArithmeticException overflow) {
            return null;
        }
        return null;
    }

    static Plan plan(long requestedItems, long availableItems,
            Profile profile) {
        if (requestedItems <= 0 || availableItems < 0
                || availableItems > requestedItems || profile == null
                || profile.outputPerPattern <= profile.selfItemsPerPattern
                || profile.selfItemsPerPattern <= 0
                || profile.netOutputPerPattern
                        != profile.outputPerPattern - profile.selfItemsPerPattern) {
            throw new IllegalArgumentException("Unsafe quantity feedback batch");
        }

        try {
            long requiredInitialItems = Math.min(
                    requestedItems, profile.selfItemsPerPattern);
            long missingSeedItems = Math.max(
                    0, requiredInitialItems - availableItems);
            long initialItems = Math.addExact(
                    availableItems, missingSeedItems);
            long remainingItems = requestedItems - initialItems;
            long patternTimes = remainingItems <= 0
                    ? 0
                    : remainingItems / profile.netOutputPerPattern
                            + (remainingItems % profile.netOutputPerPattern == 0
                                    ? 0 : 1);
            long finalItems = Math.addExact(
                    initialItems,
                    Math.multiplyExact(
                            patternTimes, profile.netOutputPerPattern));
            return new Plan(
                    patternTimes, missingSeedItems, initialItems,
                    profile.netOutputPerPattern,
                    Math.subtractExact(finalItems, requestedItems));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Quantity feedback batch overflow", overflow);
        }
    }

    static Plan plan(
            long requestedItems,
            long availableItems,
            Profile profile,
            AelisCycleSeedPolicy seedPolicy) {
        if (seedPolicy == null || profile == null) {
            throw new IllegalArgumentException("Cycle seed policy is required");
        }
        Plan base = plan(requestedItems, availableItems, profile);
        if (seedPolicy == AelisCycleSeedPolicy.MAX_THROUGHPUT
                || base.patternTimes == 0
                || base.surplusItems >= profile.selfItemsPerPattern) {
            return base;
        }
        try {
            long shortage = profile.selfItemsPerPattern - base.surplusItems;
            long additionalPatterns = shortage / profile.netOutputPerPattern
                    + (shortage % profile.netOutputPerPattern == 0 ? 0 : 1);
            long patternTimes = Math.addExact(
                    base.patternTimes, additionalPatterns);
            long finalItems = Math.addExact(
                    base.initialItems,
                    Math.multiplyExact(patternTimes, profile.netOutputPerPattern));
            return new Plan(
                    patternTimes,
                    base.missingSeedItems,
                    base.initialItems,
                    base.netOutputPerPattern,
                    Math.subtractExact(finalItems, requestedItems));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Quantity feedback seed preservation overflow", overflow);
        }
    }

    static boolean isSafeDescendantNode(DescendantNodeShape shape) {
        if (shape == null || shape.producesFeedbackKey) {
            return false;
        }
        if (shape.terminalOrEmitter) {
            return true;
        }
        return !shape.nativeBoundary
                && !shape.hasReusableInputs
                && shape.allCandidatesCompiled
                && shape.compiledCandidateCount > 0
                && shape.compiledCandidateCount == shape.candidateCount;
    }

    static boolean isSafeDescendantCandidate(
            DescendantCandidateShape shape) {
        return shape != null
                && !shape.hasContainerItems
                && (!shape.limitsQuantity || shape.safeQuantityFeedbackBatch);
    }

    static boolean isSafeDescendantInput(DescendantInputShape shape) {
        return shape != null
                && !shape.reusable
                && !shape.substitute
                && !shape.producesFeedbackKey;
    }

    static boolean isCompatibleDescendantOccurrence(
            Integer existingNodeIndex, int nodeIndex) {
        return existingNodeIndex == null || existingNodeIndex == nodeIndex;
    }
}
