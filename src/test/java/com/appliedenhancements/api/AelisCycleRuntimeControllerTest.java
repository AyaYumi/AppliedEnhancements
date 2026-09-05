package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appliedenhancements.test.TestAEKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisCycleRuntimeControllerTest {
    @Test
    void enforcesProvenStepOrderAndExposesOrdinaryConsumerFloor() {
        var makeC = new TestAEKey("make_c");
        var makeA = new TestAEKey("make_a");
        var ordinary = new TestAEKey("ordinary");
        var a = new TestAEKey("a");
        var c = new TestAEKey("c");
        var plan = new AelisCycleExecutionPlan(
                List.of(
                        new AelisCycleExecutionPlan.Step(
                                makeC, 1, Map.of(a, 1L), Set.of()),
                        new AelisCycleExecutionPlan.Step(
                                makeA, 1, Map.of(c, 1L), Set.of())),
                Map.of(a, 1L), Set.of(a, c),
                AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        var runtime = new AelisCycleRuntimeController(plan);

        assertTrue(runtime.canDispatch(makeC, Set.of(a)));
        assertFalse(runtime.canDispatch(makeA, Set.of(c)));
        assertTrue(runtime.canDispatch(ordinary, Set.of(a)));
        assertEquals(0L, runtime.maximumConsumableAmount(a, 1));
        assertEquals(2L, runtime.maximumConsumableAmount(a, 3));
        assertTrue(runtime.canDispatch(ordinary, Set.of(new TestAEKey("b"))));

        runtime.patternDispatched(makeC, 1);
        assertEquals(makeA, runtime.currentStep().orElseThrow().patternDefinition());
        assertEquals(1L, runtime.requiredRetainedAmount(c));
        assertEquals(1L, runtime.amountToRetain(c, 2, 0));
        assertEquals(0L, runtime.amountToRetain(c, 2, 1));
        runtime.patternDispatched(makeA, 1);
        assertTrue(runtime.isComplete());
        assertTrue(runtime.canDispatch(ordinary, Set.of(a)));
        assertTrue(runtime.canDispatch(makeA, Set.of(c)));
        assertEquals(1L, runtime.requiredRetainedAmount(a));
        assertEquals(1L, runtime.amountToRetain(a, 2, 0));
    }

    @Test
    void retainsOneSelfReplenishingSeedButWholeNonReplenishingBatch() {
        var selfPattern = new TestAEKey("self");
        var batchPattern = new TestAEKey("batch");
        var a = new TestAEKey("a");
        var c = new TestAEKey("c");
        var plan = new AelisCycleExecutionPlan(
                List.of(
                        new AelisCycleExecutionPlan.Step(
                                selfPattern, 10, Map.of(a, 2L), Set.of(a)),
                        new AelisCycleExecutionPlan.Step(
                                batchPattern, 6, Map.of(c, 3L), Set.of())),
                Map.of(a, 2L), Set.of(a, c),
                AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        var runtime = new AelisCycleRuntimeController(plan);

        assertEquals(2L, runtime.requiredRetainedAmount(a));
        assertEquals(18L, runtime.requiredRetainedAmount(c));
        runtime.patternDispatched(selfPattern, 9);
        assertEquals(2L, runtime.requiredRetainedAmount(a));
        runtime.patternDispatched(selfPattern, 1);
        assertEquals(18L, runtime.requiredRetainedAmount(c));
    }

    @Test
    void cyclicPhaseAllowsPrerequisitesAndWaitsForLastReturnedOutput() {
        var pattern = new TestAEKey("cycle");
        var seed = new TestAEKey("seed");
        var prerequisite = new TestAEKey("fuel");
        var ordinary = new TestAEKey("ordinary");
        var plan = new AelisCycleExecutionPlan(
                List.of(new AelisCycleExecutionPlan.Step(pattern, 1, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.MAX_THROUGHPUT,
                new AelisCycleExecutionPlan.Phase(Set.of(prerequisite), Map.of(pattern, Map.of(seed, 2L))));
        var runtime = AelisCycleRuntimeController.withCyclePhase(plan);

        assertTrue(runtime.canDispatch(pattern, Set.of()));
        assertTrue(runtime.canDispatch(prerequisite, Set.of()));
        assertFalse(runtime.canDispatch(ordinary, Set.of()));
        runtime.patternDispatched(pattern, 1);
        assertTrue(runtime.isComplete());
        assertFalse(runtime.isCyclePhaseComplete());
        assertTrue(runtime.hasActiveSeedProtection());
        assertFalse(runtime.canDispatch(ordinary, Set.of()));
        assertFalse(runtime.canDispatch(pattern, Set.of()));
        assertTrue(runtime.canDispatch(prerequisite, Set.of()));
        runtime.recordReturned(seed, 1);
        assertFalse(runtime.isCyclePhaseComplete());
        runtime.recordReturned(seed, 1);
        assertTrue(runtime.isCyclePhaseComplete());
        assertFalse(runtime.hasActiveSeedProtection());
        assertTrue(runtime.canDispatch(ordinary, Set.of()));
        assertTrue(runtime.canDispatch(pattern, Set.of()));
    }

    @Test
    void recyclesReturnedOutputInGrowingBatchesThenKeepsOnlyTheSeedFloor() {
        var pattern = new TestAEKey("cycle");
        var seed = new TestAEKey("seed");
        var runtime = selfReplenishingRuntime(pattern, seed, 7);

        runtime.patternDispatched(pattern, 1);
        assertEquals(2L, runtime.amountToRetain(seed, 2, 0));
        runtime.recordReturned(seed, 2);
        runtime.patternDispatched(pattern, 2);
        assertEquals(4L, runtime.amountToRetain(seed, 4, 0));
        runtime.recordReturned(seed, 4);
        runtime.patternDispatched(pattern, 4);
        assertEquals(1L, runtime.amountToRetain(seed, 8, 0));
        assertFalse(runtime.isCyclePhaseComplete());
        runtime.recordReturned(seed, 8);
        assertTrue(runtime.isCyclePhaseComplete());
        assertEquals(0L, runtime.amountToRetain(seed, 8, 1));
        assertEquals(1L, runtime.requiredRetainedAmount(seed));
    }

    @Test
    void returnsSurplusOnceRemainingInputsAreCovered() {
        var pattern = new TestAEKey("cycle");
        var seed = new TestAEKey("seed");
        var runtime = selfReplenishingRuntime(pattern, seed, 5);
        runtime.patternDispatched(pattern, 1);
        runtime.recordReturned(seed, 2);
        runtime.patternDispatched(pattern, 2);

        assertEquals(2L, runtime.remainingCrafts());
        assertEquals(2L, runtime.amountToRetain(seed, 4, 0));
        assertEquals(1L, runtime.amountToRetain(seed, 4, 1));
        assertEquals(0L, runtime.amountToRetain(seed, 4, 2));
        assertEquals(1L, runtime.requiredRetainedAmount(seed));
    }

    @Test
    void returnRetentionCountsAllRemainingStepsWithoutOverflow() {
        var first = new TestAEKey("first");
        var second = new TestAEKey("second");
        var seed = new TestAEKey("seed");
        var plan = new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(first, 2, Map.of(seed, 3L), Set.of(seed)),
                new AelisCycleExecutionPlan.Step(second, 3, Map.of(seed, 2L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        var runtime = new AelisCycleRuntimeController(plan);
        assertEquals(12L, runtime.amountToRetain(seed, 100, 0));
        runtime.patternDispatched(first, 1);
        assertEquals(8L, runtime.amountToRetain(seed, 100, 1));

        var huge = new AelisCycleRuntimeController(new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(first, Long.MAX_VALUE, Map.of(seed, 2L), Set.of(seed)),
                new AelisCycleExecutionPlan.Step(second, Long.MAX_VALUE, Map.of(seed, 3L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM));
        assertEquals(Long.MAX_VALUE - 1, huge.amountToRetain(seed, Long.MAX_VALUE, 1));
    }

    @Test
    void snapshotsPreserveIndependentPendingOutputCounts() {
        var pattern = new TestAEKey("cycle");
        var seed = new TestAEKey("seed");
        var runtime = selfReplenishingRuntime(pattern, seed, 3);
        runtime.patternDispatched(pattern, 2);
        runtime.recordReturned(seed, 1);
        var state = runtime.snapshot();
        var restored = AelisCycleRuntimeController.withCyclePhase(runtime.plan(), state);
        runtime.recordReturned(seed, 3);

        assertEquals(Map.of(seed, 3L), state.pendingOutputs());
        assertEquals(state, restored.snapshot());
        assertEquals(1L, restored.remainingCrafts());
        restored.patternDispatched(pattern, 1);
        assertEquals(Map.of(seed, 5L), restored.snapshot().pendingOutputs());
        restored.recordReturned(new TestAEKey("unrelated"), 100);
        assertFalse(restored.isCyclePhaseComplete());
        restored.recordReturned(seed, 5);
        assertTrue(restored.isCyclePhaseComplete());
        assertThrows(UnsupportedOperationException.class, () -> state.pendingOutputs().clear());
        assertThrows(IllegalArgumentException.class, () -> restored.recordReturned(seed, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new AelisCycleRuntimeController.State(0, 1, Map.of(seed, 0L)));
        assertEquals(Map.of(), new AelisCycleRuntimeController.State(0, 1).pendingOutputs());
    }

    @Test
    void legacyCpuConstructorDoesNotRequireNewReturnCallbacksForPhasedMetadata() {
        var pattern = new TestAEKey("cycle");
        var seed = new TestAEKey("seed");
        var ordinary = new TestAEKey("ordinary");
        var phased = selfReplenishingRuntime(pattern, seed, 2).plan();
        var runtime = new AelisCycleRuntimeController(phased);

        assertNull(runtime.plan().phase());
        assertEquals(phased.steps(), runtime.plan().steps());
        assertEquals(phased.minimumSeeds(), runtime.plan().minimumSeeds());
        assertEquals(phased.seedPolicy(), runtime.plan().seedPolicy());
        assertTrue(runtime.canDispatch(ordinary, Set.of(seed)));
        runtime.patternDispatched(pattern, 1);
        var restored = new AelisCycleRuntimeController(runtime.plan(), runtime.snapshot());
        restored.patternDispatched(pattern, 1);

        assertTrue(restored.isComplete());
        assertTrue(restored.isCyclePhaseComplete());
        assertTrue(restored.snapshot().pendingOutputs().isEmpty());
        assertTrue(restored.canDispatch(ordinary, Set.of(seed)));
        assertEquals(1L, restored.requiredRetainedAmount(seed));
    }

    @Test
    void legacyStateConstructorDoesNotEnablePhaseFromSeparatelySavedPlan() {
        var pattern = new TestAEKey("cycle");
        var seed = new TestAEKey("seed");
        var ordinary = new TestAEKey("ordinary");
        var phased = selfReplenishingRuntime(pattern, seed, 2).plan();
        var restored = new AelisCycleRuntimeController(phased,
                new AelisCycleRuntimeController.State(0, 1));

        assertNull(restored.plan().phase());
        assertTrue(restored.canDispatch(ordinary, Set.of(seed)));
        restored.patternDispatched(pattern, 1);
        assertTrue(restored.isCyclePhaseComplete());
        assertTrue(restored.snapshot().pendingOutputs().isEmpty());

        var completed = new AelisCycleRuntimeController(phased,
                new AelisCycleRuntimeController.State(1, 0, Map.of(seed, 2L)));
        assertTrue(completed.isCyclePhaseComplete());
        assertTrue(completed.snapshot().pendingOutputs().isEmpty());
        assertEquals(1L, completed.requiredRetainedAmount(seed));
    }

    @Test
    void phaseRestorationRejectsUnexplainedOrExcessPendingOutputs() {
        var pattern = new TestAEKey("cycle");
        var seed = new TestAEKey("seed");
        var unrelated = new TestAEKey("unrelated");
        var phased = selfReplenishingRuntime(pattern, seed, 3).plan();

        assertThrows(IllegalArgumentException.class, () -> AelisCycleRuntimeController.withCyclePhase(
                phased, new AelisCycleRuntimeController.State(0, 3, Map.of(seed, 1L))));
        assertThrows(IllegalArgumentException.class, () -> AelisCycleRuntimeController.withCyclePhase(
                phased, new AelisCycleRuntimeController.State(0, 2, Map.of(unrelated, 1L))));
        assertThrows(IllegalArgumentException.class, () -> AelisCycleRuntimeController.withCyclePhase(
                phased, new AelisCycleRuntimeController.State(0, 2, Map.of(seed, 3L))));

        var restored = AelisCycleRuntimeController.withCyclePhase(
                phased, new AelisCycleRuntimeController.State(0, 2, Map.of(seed, 2L)));
        assertEquals(Map.of(seed, 2L), restored.snapshot().pendingOutputs());
        var legacyPlan = new AelisCycleExecutionPlan(phased.steps(), phased.minimumSeeds(),
                phased.protectedKeys(), phased.seedPolicy());
        assertThrows(IllegalArgumentException.class, () -> AelisCycleRuntimeController.withCyclePhase(
                legacyPlan, new AelisCycleRuntimeController.State(0, 2, Map.of(seed, 1L))));
        assertTrue(new AelisCycleRuntimeController(phased,
                new AelisCycleRuntimeController.State(0, 2, Map.of(unrelated, 100L)))
                .snapshot().pendingOutputs().isEmpty());
    }

    @Test
    void pendingOutputBoundIncludesEarlierStepsAndSaturatesLargeProduction() {
        var first = new TestAEKey("first");
        var second = new TestAEKey("second");
        var seed = new TestAEKey("seed");
        var futureOnly = new TestAEKey("future");
        var phased = new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(first, 2, Map.of(seed, 1L), Set.of(seed)),
                new AelisCycleExecutionPlan.Step(second, 3, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM,
                new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(
                        first, Map.of(seed, 2L), second, Map.of(seed, 3L, futureOnly, 1L))));
        assertThrows(IllegalArgumentException.class, () -> AelisCycleRuntimeController.withCyclePhase(
                phased, new AelisCycleRuntimeController.State(1, 3, Map.of(futureOnly, 1L))));
        var restored = AelisCycleRuntimeController.withCyclePhase(phased,
                new AelisCycleRuntimeController.State(1, 2, Map.of(seed, 7L, futureOnly, 1L)));
        assertEquals(7L, restored.snapshot().pendingOutputs().get(seed).longValue());

        var huge = new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(first, Long.MAX_VALUE, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM,
                new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(first, Map.of(seed, 2L))));
        assertEquals(Long.MAX_VALUE, AelisCycleRuntimeController.withCyclePhase(huge,
                new AelisCycleRuntimeController.State(1, 0, Map.of(seed, Long.MAX_VALUE)))
                .snapshot().pendingOutputs().get(seed).longValue());
    }

    private static AelisCycleRuntimeController selfReplenishingRuntime(
            TestAEKey pattern, TestAEKey seed, long crafts) {
        return AelisCycleRuntimeController.withCyclePhase(new AelisCycleExecutionPlan(
                List.of(new AelisCycleExecutionPlan.Step(pattern, crafts, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM,
                new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(pattern, Map.of(seed, 2L)))));
    }
}
