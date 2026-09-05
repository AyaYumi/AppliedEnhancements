package com.github.appliedenhancements.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CraftingCalculationProgressHandleTest {
    @Test
    void tracksAttemptAndClampsExecutionProgress() {
        var handle = new CraftingCalculationProgressHandle(7);
        handle.beginAttempt(false);
        handle.beginAelisExecution(2);
        handle.executionStep();
        handle.executionStep();
        handle.executionStep();

        var active = handle.snapshot(1);
        assertEquals(CraftingCalculationProgressPhase.AELIS_EXECUTING, active.phase());
        assertEquals(2, active.completedUnits());
        assertEquals(2, active.totalUnits());
        assertEquals(3, active.processedSteps());
        assertEquals(1, active.attempt());

        handle.complete(AelisCalculationPath.AELIS);
        handle.executionStep();
        var completed = handle.snapshot(2);
        assertTrue(handle.terminal());
        assertEquals(CraftingCalculationProgressPhase.COMPLETED, completed.phase());
        assertEquals(2, completed.completedUnits());
        assertEquals(3, completed.processedSteps());
        assertEquals(completed.elapsedMillis(), handle.snapshot(3).elapsedMillis());
    }

    @Test
    void terminalStateCannotBeOverwritten() {
        var handle = new CraftingCalculationProgressHandle(1);
        handle.cancel();
        handle.beginAe2(AelisCalculationPath.AE2_NATIVE);
        handle.fail();
        assertEquals(CraftingCalculationProgressPhase.CANCELLED, handle.snapshot(1).phase());
    }

    @Test
    void ae2PhaseRejectsNonAe2Paths() {
        var handle = new CraftingCalculationProgressHandle(1);
        assertThrows(
                IllegalArgumentException.class,
                () -> handle.beginAe2(AelisCalculationPath.AELIS));
    }

    @Test
    void generationMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new CraftingCalculationProgressHandle(0));
    }
}
