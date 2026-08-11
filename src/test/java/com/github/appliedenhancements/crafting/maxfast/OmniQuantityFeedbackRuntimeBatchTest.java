package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OmniQuantityFeedbackRuntimeBatchTest {
    @Test
    void batchesThreeHundredTwentyMillionTemplatesWhenMaterialsExist() {
        var plan = plan(320_000_000).orElseThrow();

        assertEquals(320_000_000, plan.remainingItems());
        assertEquals(160_000_000, plan.patternTimes());
        assertEquals(160_000_000, plan.feedbackItems());
        assertEquals(1_120_000_000L,
                plan.nonFeedbackRequirements().get("diamond"));
        assertEquals(160_000_000L,
                plan.nonFeedbackRequirements().get("netherrack"));
        assertTrue(OmniQuantityFeedbackRuntimeBatch.allNonFeedbackInputsAvailable(
                plan, (key, requested) -> Long.MAX_VALUE));
    }

    @Test
    void ownerExtractionSuppliesRemainingCountToTheGuard() {
        var plan = plan(12).orElseThrow();

        assertEquals(12, plan.remainingItems());
        assertEquals(6, plan.patternTimes());
        assertEquals(6, plan.feedbackItems());
        assertEquals(42, plan.nonFeedbackRequirements().get("diamond"));
    }

    @Test
    void nodeAmountScalesRemainingItemsBeforePatternRounding() {
        var plan = OmniQuantityFeedbackRuntimeBatch.plan(
                3, 5, 4,
                List.of(
                        input("template", 1, 1, true),
                        input("iron", 1, 1, false)))
                .orElseThrow();

        assertEquals(15, plan.remainingItems());
        assertEquals(4, plan.patternTimes());
        assertEquals(4, plan.feedbackItems());
        assertEquals(Map.of("iron", 4L), plan.nonFeedbackRequirements());
    }

    @Test
    void childAmountAndMultiplierBothScaleDirectRequirements() {
        var plan = OmniQuantityFeedbackRuntimeBatch.plan(
                1, 8, 2,
                List.of(
                        input("template", 1, 1, true),
                        input("diamond", 3, 2, false)))
                .orElseThrow();

        assertEquals(4, plan.patternTimes());
        assertEquals(Map.of("diamond", 24L),
                plan.nonFeedbackRequirements());
    }

    @Test
    void duplicateMaterialSlotsAreAggregatedBeforeAvailabilityCheck() {
        var result = OmniQuantityFeedbackRuntimeBatch.plan(
                1, 10, 2,
                List.of(
                        input("template", 1, 1, true),
                        input("diamond", 1, 4, false),
                        input("diamond", 1, 3, false)));
        var plan = result.orElseThrow();

        assertEquals(Map.of("diamond", 35L),
                plan.nonFeedbackRequirements());
        assertFalse(OmniQuantityFeedbackRuntimeBatch.allNonFeedbackInputsAvailable(
                plan, (key, requested) -> 34));
        assertTrue(OmniQuantityFeedbackRuntimeBatch.allNonFeedbackInputsAvailable(
                plan, (key, requested) -> 35));
    }

    @Test
    void rejectsInvalidFeedbackShapesAndLongOverflow() {
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                1, Long.MAX_VALUE, 2,
                List.of(input("template", 2, 2, true)))
                .isEmpty());
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                1, 1, 2,
                List.of(input("diamond", 1, 1, false)))
                .isEmpty());
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                1, 1, 2,
                List.of(
                        input("a", 1, 1, true),
                        input("b", 1, 1, true)))
                .isEmpty());
    }

    @Test
    void rejectsNodeAndChildRequirementMultiplicationOverflow() {
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                Long.MAX_VALUE, 2, 1, safeInputs()).isEmpty());
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                1, 1, 1,
                List.of(
                        input("template", 1, 1, true),
                        input("diamond", Long.MAX_VALUE, 2, false)))
                .isEmpty());
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                1, Long.MAX_VALUE, 1,
                List.of(
                        input("template", 1, 1, true),
                        input("diamond", 2, 1, false)))
                .isEmpty());
    }

    @Test
    void rejectsOverflowWhileMergingDuplicateRequirements() {
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                1, 1, 1,
                List.of(
                        input("template", 1, 1, true),
                        input("diamond", Long.MAX_VALUE, 1, false),
                        input("diamond", 1, 1, false)))
                .isEmpty());
    }

    @Test
    void rejectsNegativeAvailabilityEvenForPositiveRequirements() {
        var plan = plan(2).orElseThrow();

        assertFalse(OmniQuantityFeedbackRuntimeBatch.allNonFeedbackInputsAvailable(
                plan, (key, requested) -> -1));
    }

    @Test
    void rejectsEmptyRemainingWorkBecauseOwnerAlreadyReturnsEarly() {
        assertTrue(OmniQuantityFeedbackRuntimeBatch.plan(
                1, 0, 2, safeInputs()).isEmpty());
    }

    private static java.util.Optional<OmniQuantityFeedbackRuntimeBatch.Plan<String>>
            plan(long remaining) {
        return OmniQuantityFeedbackRuntimeBatch.plan(
                1, remaining, 2, safeInputs());
    }

    private static List<OmniQuantityFeedbackRuntimeBatch.Input<String>> safeInputs() {
        return List.of(
                input("template", 1, 1, true),
                input("diamond", 1, 7, false),
                input("netherrack", 1, 1, false));
    }

    private static OmniQuantityFeedbackRuntimeBatch.Input<String> input(
            String key, long amount, long multiplier, boolean feedback) {
        return new OmniQuantityFeedbackRuntimeBatch.Input<>(
                key, amount, multiplier, feedback);
    }
}
