package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.appliedenhancements.test.TestAEKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisCycleExecutionPlanTest {
    @Test
    void planAndStepsAreValidatedAndDefensivelyCopied() {
        var pattern = new TestAEKey("pattern_a");
        var seed = new TestAEKey("seed_a");
        var inputs = new LinkedHashMap<appeng.api.stacks.AEKey, Long>();
        inputs.put(seed, 2L);
        var steps = new ArrayList<AelisCycleExecutionPlan.Step>();
        steps.add(new AelisCycleExecutionPlan.Step(
                pattern, 8, inputs, Set.of(seed)));

        var plan = new AelisCycleExecutionPlan(
                steps, Map.of(seed, 2L), Set.of(seed),
                AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        inputs.clear();
        steps.clear();

        assertEquals(1, plan.steps().size());
        assertEquals(Map.of(seed, 2L), plan.steps().getFirst().inputsPerCraft());
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new AelisCycleExecutionPlan.Step(
                        pattern, 0, Map.of(seed, 1L), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AelisCycleExecutionPlan.Step(
                        pattern, 1, Map.of(seed, 1L),
                        new LinkedHashSet<>(Set.of(new TestAEKey("other")))));
    }

    @Test
    void legacyConstructorKeepsFormerMaximumThroughputBehavior() {
        var pattern = new TestAEKey("pattern");
        var seed = new TestAEKey("seed");
        var plan = new AelisCycleExecutionPlan(
                List.of(new AelisCycleExecutionPlan.Step(
                        pattern, 1, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed));

        assertEquals(AelisCycleSeedPolicy.MAX_THROUGHPUT, plan.seedPolicy());
        assertNull(plan.phase());
        assertNull(new AelisCycleExecutionPlan(plan.steps(), plan.minimumSeeds(), plan.protectedKeys(),
                AelisCycleSeedPolicy.PRESERVE_MINIMUM).phase());
    }

    @Test
    void phaseCopiesNestedOutputsAndRejectsInvalidAmounts() {
        var pattern = new TestAEKey("pattern");
        var output = new TestAEKey("output");
        var prerequisite = new TestAEKey("prerequisite");
        var prerequisites = new LinkedHashSet<appeng.api.stacks.AEKey>(Set.of(prerequisite));
        var amounts = new LinkedHashMap<appeng.api.stacks.AEKey, Long>();
        amounts.put(output, 2L);
        var outputs = new LinkedHashMap<appeng.api.stacks.AEKey, Map<appeng.api.stacks.AEKey, Long>>();
        outputs.put(pattern, amounts);
        var phase = new AelisCycleExecutionPlan.Phase(prerequisites, outputs);
        prerequisites.clear();
        amounts.clear();
        outputs.clear();

        assertEquals(Set.of(prerequisite), phase.prerequisitePatterns());
        assertEquals(Map.of(pattern, Map.of(output, 2L)), phase.outputsPerPattern());
        assertThrows(UnsupportedOperationException.class,
                () -> phase.outputsPerPattern().get(pattern).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> phase.prerequisitePatterns().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(pattern, Map.of(output, 0L))));
        assertThrows(IllegalArgumentException.class,
                () -> new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(pattern, Map.of(output, -1L))));
    }

    @Test
    void phaseMustCoverEveryScheduledPattern() {
        var first = new TestAEKey("first");
        var second = new TestAEKey("second");
        var seed = new TestAEKey("seed");
        var steps = List.of(
                new AelisCycleExecutionPlan.Step(first, 1, Map.of(seed, 1L), Set.of(seed)),
                new AelisCycleExecutionPlan.Step(second, 1, Map.of(seed, 1L), Set.of(seed)));
        assertThrows(IllegalArgumentException.class, () -> new AelisCycleExecutionPlan(
                steps, Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM,
                new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(first, Map.of(seed, 2L)))));

        var complete = new AelisCycleExecutionPlan(steps, Map.of(seed, 1L), Set.of(seed),
                AelisCycleSeedPolicy.PRESERVE_MINIMUM, new AelisCycleExecutionPlan.Phase(Set.of(),
                        Map.of(first, Map.of(seed, 2L), second, Map.of(seed, 3L))));
        assertEquals(Set.of(first, second), complete.phase().outputsPerPattern().keySet());
    }
}
