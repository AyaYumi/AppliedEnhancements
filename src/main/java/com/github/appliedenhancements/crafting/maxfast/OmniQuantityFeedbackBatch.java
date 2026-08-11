package com.github.appliedenhancements.crafting.maxfast;

import java.util.List;

/**
 * Proof policy for batching a quantity-limited pattern whose primary output is
 * also one of its consumable inputs.
 *
 * <p>The planner may aggregate this shape because the output node drains all
 * available requested items before it asks for pattern inputs. Every craft
 * except the final one therefore contributes all of its output to the still
 * outstanding request, while only the final craft can leave surplus. The
 * feedback input can consequently be requested once for the aggregated craft
 * count, provided it is represented by a distinct recursion-filtered tree
 * occurrence rather than a graph self-edge.</p>
 */
final class OmniQuantityFeedbackBatch {
    private OmniQuantityFeedbackBatch() {
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

    record Plan(long patternTimes, long selfRequestMultipliers,
            long selfRequestedItems, long surplusItems) {
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

    static Plan plan(long requestedItems, Shape shape) {
        if (requestedItems <= 0 || !isSafe(shape)) {
            throw new IllegalArgumentException("Unsafe quantity feedback batch");
        }

        InputShape selfInput = null;
        for (InputShape input : shape.inputs) {
            if (input.selfInput) {
                selfInput = input;
                break;
            }
        }
        if (selfInput == null) {
            throw new IllegalArgumentException("Missing quantity feedback input");
        }

        long patternTimes = requestedItems / shape.outputPerPattern
                + (requestedItems % shape.outputPerPattern == 0 ? 0 : 1);
        long selfRequestMultipliers = Math.multiplyExact(
                patternTimes, selfInput.multiplier);
        long selfRequestedItems = Math.multiplyExact(
                selfInput.amount, selfRequestMultipliers);
        long remainder = requestedItems % shape.outputPerPattern;
        long surplusItems = remainder == 0
                ? 0
                : shape.outputPerPattern - remainder;
        return new Plan(patternTimes, selfRequestMultipliers,
                selfRequestedItems, surplusItems);
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
