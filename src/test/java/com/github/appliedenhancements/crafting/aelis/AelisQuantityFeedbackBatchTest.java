package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appliedenhancements.api.AelisCycleSeedPolicy;
import java.util.List;

import org.junit.jupiter.api.Test;

class AelisQuantityFeedbackBatchTest {
    @Test
    void usesNetGrowthAndOnlyReportsTheStartupSeed() {
        var profile = AelisQuantityFeedbackBatch.profile(
                safeTemplateDuplication());

        assertPlan(profile, 1, 0, 0, 1, 0);
        assertPlan(profile, 3, 0, 2, 1, 0);
        assertPlan(profile, 5, 0, 4, 1, 0);
        assertPlan(profile, 33, 0, 32, 1, 0);
    }

    @Test
    void existingStockStartsAndContributesToTheFeedbackCycle() {
        var profile = AelisQuantityFeedbackBatch.profile(
                oneToNineGrowth());
        var plan = AelisQuantityFeedbackBatch.plan(
                82_000_000, 64, profile);

        assertEquals(9, profile.outputPerPattern());
        assertEquals(1, profile.selfItemsPerPattern());
        assertEquals(8, profile.netOutputPerPattern());
        assertEquals(10_249_992, plan.patternTimes());
        assertEquals(0, plan.missingSeedItems());
        assertEquals(64, plan.initialItems());
        assertEquals(0, plan.surplusItems());
    }

    @Test
    void absentStockNeedsOneSeedAndReusesItsGrowth() {
        var profile = AelisQuantityFeedbackBatch.profile(oneToNineGrowth());
        var plan = AelisQuantityFeedbackBatch.plan(
                82_000_000, 0, profile);

        assertEquals(10_250_000, plan.patternTimes());
        assertEquals(1, plan.missingSeedItems());
        assertEquals(1, plan.initialItems());
        assertEquals(1, plan.surplusItems());
    }

    @Test
    void preserveMinimumOverproducesWithoutReducingRequestedOutput() {
        var profile = AelisQuantityFeedbackBatch.profile(oneToNineGrowth());
        var preserved = AelisQuantityFeedbackBatch.plan(
                81, 1, profile, AelisCycleSeedPolicy.PRESERVE_MINIMUM);

        assertEquals(11, preserved.patternTimes());
        assertTrue(preserved.surplusItems() >= profile.selfItemsPerPattern());
        assertEquals(0, preserved.missingSeedItems());
    }

    @Test
    void maximumThroughputKeepsTheFormerQuantityPlan() {
        var profile = AelisQuantityFeedbackBatch.profile(oneToNineGrowth());
        var direct = AelisQuantityFeedbackBatch.plan(80, 1, profile);
        var throughput = AelisQuantityFeedbackBatch.plan(
                80, 1, profile, AelisCycleSeedPolicy.MAX_THROUGHPUT);

        assertEquals(direct, throughput);
    }

    @Test
    void preserveMinimumDoesNotDemandALargerSeedWhenNoCycleWillRun() {
        var shape = shape(
                1, false, true, true, 1, 20,
                List.of(
                        input(true, true, true, true, 10, 1),
                        input(false, true, true, true, 1, 1)));
        var profile = AelisQuantityFeedbackBatch.profile(shape);

        assertEquals(
                AelisQuantityFeedbackBatch.plan(5, 0, profile),
                AelisQuantityFeedbackBatch.plan(
                        5, 0, profile,
                        AelisCycleSeedPolicy.PRESERVE_MINIMUM));
    }

    @Test
    void requestsBelowTheCycleSeedBecomeDirectMissingItems() {
        var shape = shape(
                1, false, true, true, 1, 20,
                List.of(
                        input(true, true, true, true, 10, 1),
                        input(false, true, true, true, 1, 1)));
        var plan = AelisQuantityFeedbackBatch.plan(
                5, 0, AelisQuantityFeedbackBatch.profile(shape));

        assertEquals(0, plan.patternTimes());
        assertEquals(5, plan.missingSeedItems());
        assertEquals(5, plan.initialItems());
        assertEquals(0, plan.surplusItems());
    }

    @Test
    void rejectsAPlanWhoseFinalSurplusWouldOverflowLong() {
        assertThrows(IllegalArgumentException.class,
                () -> AelisQuantityFeedbackBatch.plan(
                        Long.MAX_VALUE, 0,
                        AelisQuantityFeedbackBatch.profile(oneToNineGrowth())));
    }

    @Test
    void rejectsCandidateContainerPatternAndOutputAmbiguity() {
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                2, false, true, true, 1, 2, safeInputs())));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, true, true, true, 1, 2, safeInputs())));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, false, true, 1, 2, safeInputs())));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, false, 1, 2, safeInputs())));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 2, 2, safeInputs())));
    }

    @Test
    void rejectsMissingDuplicateOrNonExactFeedbackInputs() {
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(false, true, true, true, 1, 1)))));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 3,
                List.of(
                        input(true, true, true, true, 1, 1),
                        input(true, true, true, true, 1, 1)))));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(true, false, true, true, 1, 1)))));
    }

    @Test
    void rejectsRemaindersReusableInputsAndNonPositiveFeedback() {
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(true, true, true, false, 1, 1)))));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 2,
                List.of(input(true, true, false, true, 1, 1)))));
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, false, true, true, 1, 1,
                List.of(input(true, true, true, true, 1, 1)))));
    }

    @Test
    void rejectsPerCraftLongOverflowAndUnsafePlanningRequests() {
        var overflow = shape(
                1, false, true, true, 1, Long.MAX_VALUE,
                List.of(input(
                        true, true, true, true, Long.MAX_VALUE, 2)));

        assertFalse(AelisQuantityFeedbackBatch.isSafe(overflow));
        assertNull(AelisQuantityFeedbackBatch.profile(overflow));
        assertThrows(IllegalArgumentException.class,
                () -> AelisQuantityFeedbackBatch.plan(
                        1, 0, AelisQuantityFeedbackBatch.profile(overflow)));
        assertThrows(IllegalArgumentException.class,
                () -> AelisQuantityFeedbackBatch.plan(
                        0, 0,
                        AelisQuantityFeedbackBatch.profile(
                                safeTemplateDuplication())));
        assertThrows(IllegalArgumentException.class,
                () -> AelisQuantityFeedbackBatch.plan(
                        1, 2,
                        AelisQuantityFeedbackBatch.profile(
                                safeTemplateDuplication())));
    }

    @Test
    void rejectsNonUnitProducingNodes() {
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                2, 1, false, true, true, true,
                1, 2, safeInputs())));
    }

    @Test
    void rejectsFeedbackOccurrencesWithAnAlternateRecipe() {
        // Counterexample: P consumes K and produces 2K, but the split feedback
        // occurrence can still craft K through alternate Q. Aggregating P's
        // self demand would then change Q selection and recursion semantics.
        assertFalse(AelisQuantityFeedbackBatch.isSafe(shape(
                1, 1, false, true, true, false,
                1, 2, safeInputs())));
    }

    @Test
    void rejectsHiddenFeedbackFromDescendantContainerBoundary() {
        // P(K + 2B -> 2K), Q(T -> B), T remainingKey=K. Q is represented by
        // a native/container boundary, so its hidden K return would be visible
        // between native P repetitions and must forbid aggregated P execution.
        assertFalse(AelisQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, false, true, false, true, 1, 1)));
        assertFalse(AelisQuantityFeedbackBatch.isSafeDescendantCandidate(
                new AelisQuantityFeedbackBatch.DescendantCandidateShape(
                        true, false, false)));
    }

    @Test
    void acceptsOnlyCompleteExactDescendantChainsThatCannotProduceFeedback() {
        assertTrue(AelisQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, false, false, false, true, 2, 2)));
        assertTrue(AelisQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, true, false, false, false, 0, 0)));
        assertTrue(AelisQuantityFeedbackBatch.isSafeDescendantCandidate(
                new AelisQuantityFeedbackBatch.DescendantCandidateShape(
                        false, false, false)));
        assertTrue(AelisQuantityFeedbackBatch.isSafeDescendantInput(
                new AelisQuantityFeedbackBatch.DescendantInputShape(
                        false, false, false)));

        assertFalse(AelisQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(true, true, false, false, false, 0, 0)));
        assertFalse(AelisQuantityFeedbackBatch.isSafeDescendantNode(
                descendantNode(false, false, false, false, false, 1, 2)));
        assertFalse(AelisQuantityFeedbackBatch.isSafeDescendantInput(
                new AelisQuantityFeedbackBatch.DescendantInputShape(
                        false, true, false)));
        assertFalse(AelisQuantityFeedbackBatch.isSafeDescendantInput(
                new AelisQuantityFeedbackBatch.DescendantInputShape(
                        false, false, true)));
    }

    @Test
    void rejectsTheSameDescendantKeySplitAcrossDifferentOccurrences() {
        // Pure exact descendants can still be order-sensitive when recursion
        // contexts split the same key into distinct graph nodes. Per-pattern
        // execution may pass one occurrence's surplus to a later input, while
        // slot-wise batching cannot preserve that interleaving.
        assertTrue(AelisQuantityFeedbackBatch
                .isCompatibleDescendantOccurrence(null, 7));
        assertTrue(AelisQuantityFeedbackBatch
                .isCompatibleDescendantOccurrence(7, 7));
        assertFalse(AelisQuantityFeedbackBatch
                .isCompatibleDescendantOccurrence(7, 11));
    }

    private static void assertPlan(AelisQuantityFeedbackBatch.Profile profile,
            long requestedItems, long availableItems,
            long expectedPatternTimes, long expectedMissingSeed,
            long expectedSurplus) {
        var plan = AelisQuantityFeedbackBatch.plan(
                requestedItems, availableItems, profile);

        assertEquals(expectedPatternTimes, plan.patternTimes());
        assertEquals(expectedMissingSeed, plan.missingSeedItems());
        assertEquals(expectedSurplus, plan.surplusItems());
    }

    private static AelisQuantityFeedbackBatch.Shape safeTemplateDuplication() {
        return shape(1, false, true, true, 1, 2, safeInputs());
    }

    private static AelisQuantityFeedbackBatch.Shape oneToNineGrowth() {
        return shape(
                1, false, true, true, 1, 9,
                List.of(
                        input(true, true, true, true, 1, 1),
                        input(false, true, true, true, 8, 1)));
    }

    private static List<AelisQuantityFeedbackBatch.InputShape> safeInputs() {
        return List.of(
                input(true, true, true, true, 1, 1),
                input(false, true, true, true, 7, 1));
    }

    private static AelisQuantityFeedbackBatch.Shape shape(
            int candidateCount, boolean hasContainerItems,
            boolean deterministicPattern, boolean primaryOutputOnly,
            int outputEntryCount, long outputPerPattern,
            List<AelisQuantityFeedbackBatch.InputShape> inputs) {
        return shape(1, candidateCount, hasContainerItems,
                deterministicPattern, primaryOutputOnly, true,
                outputEntryCount, outputPerPattern, inputs);
    }

    private static AelisQuantityFeedbackBatch.Shape shape(
            long producingNodeAmount, int candidateCount,
            boolean hasContainerItems, boolean deterministicPattern,
            boolean primaryOutputOnly, boolean feedbackOccurrenceTerminal,
            int outputEntryCount, long outputPerPattern,
            List<AelisQuantityFeedbackBatch.InputShape> inputs) {
        return new AelisQuantityFeedbackBatch.Shape(
                producingNodeAmount, candidateCount, hasContainerItems,
                deterministicPattern, primaryOutputOnly,
                feedbackOccurrenceTerminal, outputEntryCount,
                outputPerPattern, inputs);
    }

    private static AelisQuantityFeedbackBatch.InputShape input(
            boolean selfInput, boolean exact, boolean consumable,
            boolean remainderFree, long amount, long multiplier) {
        return new AelisQuantityFeedbackBatch.InputShape(
                selfInput, exact, consumable, remainderFree,
                amount, multiplier);
    }

    private static AelisQuantityFeedbackBatch.DescendantNodeShape descendantNode(
            boolean producesFeedbackKey, boolean terminalOrEmitter,
            boolean nativeBoundary, boolean hasReusableInputs,
            boolean allCandidatesCompiled, int compiledCandidateCount,
            int candidateCount) {
        return new AelisQuantityFeedbackBatch.DescendantNodeShape(
                producesFeedbackKey, terminalOrEmitter, nativeBoundary,
                hasReusableInputs, allCandidatesCompiled,
                compiledCandidateCount, candidateCount);
    }
}
