package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class OmniQuantityFeedbackBatchTest {
    @Test
    void batchesOneTwoThreeAndSeventeenCrafts() {
        var shape = safeTemplateDuplication();

        assertPlan(shape, 1, 1);
        assertPlan(shape, 3, 2);
        assertPlan(shape, 5, 3);
        assertPlan(shape, 33, 17);
    }

    @Test
    void exactOutputMultiplesDoNotLeaveSurplus() {
        var plan = OmniQuantityFeedbackBatch.plan(34, safeTemplateDuplication());

        assertEquals(17, plan.patternTimes());
        assertEquals(17, plan.selfRequestMultipliers());
        assertEquals(17, plan.selfRequestedItems());
        assertEquals(0, plan.surplusItems());
    }

    @Test
    void rejectsCandidateContainerPatternAndOutputAmbiguity() {
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                2, false, true, true, 1, 2, safeInputs())));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, true, true, true, 1, 2, safeInputs())));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, false, true, 1, 2, safeInputs())));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, false, 1, 2, safeInputs())));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 2, 2, safeInputs())));
    }

    @Test
    void rejectsMissingDuplicateOrNonExactFeedbackInputs() {
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(false, true, true, true, 1, 1)))));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 3,
                List.of(
                        input(true, true, true, true, 1, 1),
                        input(true, true, true, true, 1, 1)))));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(true, false, true, true, 1, 1)))));
    }

    @Test
    void rejectsRemaindersReusableInputsAndNonPositiveFeedback() {
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(true, true, true, false, 1, 1)))));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(true, true, false, true, 1, 1)))));
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 1,
                List.of(input(true, true, true, true, 1, 1)))));
    }

    @Test
    void rejectsPerCraftLongOverflowAndUnsafePlanningRequests() {
        var overflow = shape(
                1, false, true, true, 1, Long.MAX_VALUE,
                List.of(input(
                        true, true, true, true, Long.MAX_VALUE, 2)));

        assertFalse(OmniQuantityFeedbackBatch.isSafe(overflow));
        assertThrows(IllegalArgumentException.class,
                () -> OmniQuantityFeedbackBatch.plan(1, overflow));
        assertThrows(IllegalArgumentException.class,
                () -> OmniQuantityFeedbackBatch.plan(0, safeTemplateDuplication()));
    }

    @Test
    void rejectsNonUnitProducingNodes() {
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                2, 1, false, true, true, true,
                1, 2, safeInputs())));
    }

    @Test
    void rejectsFeedbackOccurrencesWithAnAlternateRecipe() {
        // Counterexample: P consumes K and produces 2K, but the split feedback
        // occurrence can still craft K through alternate Q. Aggregating P's
        // self demand would then change Q selection and recursion semantics.
        assertFalse(OmniQuantityFeedbackBatch.isSafe(shape(
                1, 1, false, true, true, false,
                1, 2, safeInputs())));
    }

    @Test
    void rejectsHiddenFeedbackFromDescendantContainerBoundary() {
        // P(K + 2B -> 2K), Q(T -> B), T remainingKey=K. Q is represented by
        // a native/container boundary, so its hidden K return would be visible
        // between native P repetitions and must forbid aggregated P execution.
        assertFalse(OmniQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, false, true, false, true, 1, 1)));
        assertFalse(OmniQuantityFeedbackBatch.isSafeDescendantCandidate(
                new OmniQuantityFeedbackBatch.DescendantCandidateShape(
                        true, false, false)));
    }

    @Test
    void acceptsOnlyCompleteExactDescendantChainsThatCannotProduceFeedback() {
        assertTrue(OmniQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, false, false, false, true, 2, 2)));
        assertTrue(OmniQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, true, false, false, false, 0, 0)));
        assertTrue(OmniQuantityFeedbackBatch.isSafeDescendantCandidate(
                new OmniQuantityFeedbackBatch.DescendantCandidateShape(
                        false, false, false)));
        assertTrue(OmniQuantityFeedbackBatch.isSafeDescendantInput(
                new OmniQuantityFeedbackBatch.DescendantInputShape(
                        false, false, false)));

        assertFalse(OmniQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(true, true, false, false, false, 0, 0)));
        assertFalse(OmniQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, false, false, false, false, 1, 2)));
        assertFalse(OmniQuantityFeedbackBatch.isSafeDescendantInput(
                new OmniQuantityFeedbackBatch.DescendantInputShape(
                        false, true, false)));
        assertFalse(OmniQuantityFeedbackBatch.isSafeDescendantInput(
                new OmniQuantityFeedbackBatch.DescendantInputShape(
                        false, false, true)));
    }

    @Test
    void rejectsTheSameDescendantKeySplitAcrossDifferentOccurrences() {
        // Pure exact descendants can still be order-sensitive when recursion
        // contexts split the same key into distinct graph nodes. Per-pattern
        // execution may pass one occurrence's surplus to a later input, while
        // slot-wise batching cannot preserve that interleaving.
        assertTrue(OmniQuantityFeedbackBatch
                .isCompatibleDescendantOccurrence(null, 7));
        assertTrue(OmniQuantityFeedbackBatch
                .isCompatibleDescendantOccurrence(7, 7));
        assertFalse(OmniQuantityFeedbackBatch
                .isCompatibleDescendantOccurrence(7, 11));
    }

    private static void assertPlan(OmniQuantityFeedbackBatch.Shape shape,
            long requestedItems, long expectedPatternTimes) {
        var plan = OmniQuantityFeedbackBatch.plan(requestedItems, shape);

        assertTrue(OmniQuantityFeedbackBatch.isSafe(shape));
        assertEquals(expectedPatternTimes, plan.patternTimes());
        assertEquals(expectedPatternTimes, plan.selfRequestMultipliers());
        assertEquals(expectedPatternTimes, plan.selfRequestedItems());
        assertEquals(1, plan.surplusItems());
    }

    private static OmniQuantityFeedbackBatch.Shape safeTemplateDuplication() {
        return shape(1, false, true, true, 1, 2, safeInputs());
    }

    private static List<OmniQuantityFeedbackBatch.InputShape> safeInputs() {
        return List.of(
                input(true, true, true, true, 1, 1),
                input(false, true, true, true, 7, 1));
    }

    private static OmniQuantityFeedbackBatch.Shape shape(
            int candidateCount, boolean hasContainerItems,
            boolean deterministicPattern, boolean primaryOutputOnly,
            int outputEntryCount, long outputPerPattern,
            List<OmniQuantityFeedbackBatch.InputShape> inputs) {
        return shape(1, candidateCount, hasContainerItems,
                deterministicPattern, primaryOutputOnly, true,
                outputEntryCount, outputPerPattern, inputs);
    }

    private static OmniQuantityFeedbackBatch.Shape shape(
            long producingNodeAmount, int candidateCount,
            boolean hasContainerItems, boolean deterministicPattern,
            boolean primaryOutputOnly, boolean feedbackOccurrenceTerminal,
            int outputEntryCount, long outputPerPattern,
            List<OmniQuantityFeedbackBatch.InputShape> inputs) {
        return new OmniQuantityFeedbackBatch.Shape(
                producingNodeAmount, candidateCount, hasContainerItems,
                deterministicPattern, primaryOutputOnly,
                feedbackOccurrenceTerminal, outputEntryCount,
                outputPerPattern, inputs);
    }

    private static OmniQuantityFeedbackBatch.InputShape input(
            boolean selfInput, boolean exact, boolean consumable,
            boolean remainderFree, long amount, long multiplier) {
        return new OmniQuantityFeedbackBatch.InputShape(
                selfInput, exact, consumable, remainderFree,
                amount, multiplier);
    }

    private static OmniQuantityFeedbackBatch.DescendantNodeShape descendantNode(
            boolean producesFeedbackKey, boolean terminalOrEmitter,
            boolean nativeBoundary, boolean hasReusableInputs,
            boolean allCandidatesCompiled, int compiledCandidateCount,
            int candidateCount) {
        return new OmniQuantityFeedbackBatch.DescendantNodeShape(
                producesFeedbackKey, terminalOrEmitter, nativeBoundary,
                hasReusableInputs, allCandidatesCompiled,
                compiledCandidateCount, candidateCount);
    }
}
