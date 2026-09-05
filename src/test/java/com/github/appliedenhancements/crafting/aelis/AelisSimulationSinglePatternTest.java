package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AelisSimulationSinglePatternTest {
    @Test
    void acceptsOneRequestedUnitFromOneOutput() {
        assertTrue(AelisSimulationSinglePattern.isAtMostOnePattern(1, 1, 1));
    }

    @Test
    void acceptsRequestCoveredByOneLargerOutput() {
        assertTrue(AelisSimulationSinglePattern.isAtMostOnePattern(2, 3, 6));
    }

    @Test
    void rejectsASecondRequiredPattern() {
        assertFalse(AelisSimulationSinglePattern.isAtMostOnePattern(1, 2, 1));
        assertFalse(AelisSimulationSinglePattern.isAtMostOnePattern(3, 2, 5));
    }

    @Test
    void rejectsInvalidAndOverflowingVolumes() {
        assertFalse(AelisSimulationSinglePattern.isAtMostOnePattern(0, 1, 1));
        assertFalse(AelisSimulationSinglePattern.isAtMostOnePattern(1, 0, 1));
        assertFalse(AelisSimulationSinglePattern.isAtMostOnePattern(1, 1, 0));
        assertFalse(AelisSimulationSinglePattern.isAtMostOnePattern(
                Long.MAX_VALUE, 2, Long.MAX_VALUE));
    }

    @Test
    void acceptsLargestNonOverflowingSinglePatternRequest() {
        assertTrue(AelisSimulationSinglePattern.isAtMostOnePattern(
                Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertTrue(AelisSimulationSinglePattern.isAtMostOnePattern(
                1, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void derivesLargestOnePatternReplayChunk() {
        assertEquals(1,
                AelisSimulationSinglePattern
                        .maxRequestMultipliersPerPattern(1, 1));
        assertEquals(3,
                AelisSimulationSinglePattern
                        .maxRequestMultipliersPerPattern(2, 6));
        assertEquals(0,
                AelisSimulationSinglePattern
                        .maxRequestMultipliersPerPattern(3, 2));
    }

    @Test
    void boundsLinearReplayByPatternSteps() {
        assertTrue(AelisSimulationSinglePattern.canReplayWithin(
                1, 8, 1, 8));
        assertTrue(AelisSimulationSinglePattern.canReplayWithin(
                2, 12, 6, 4));
        assertFalse(AelisSimulationSinglePattern.canReplayWithin(
                1, 9, 1, 8));
        assertFalse(AelisSimulationSinglePattern.canReplayWithin(
                3, 1, 2, 8));
        assertFalse(AelisSimulationSinglePattern.canReplayWithin(
                1, 1, 1, 0));
    }

    @Test
    void replayStepCalculationDoesNotOverflow() {
        assertTrue(AelisSimulationSinglePattern.canReplayWithin(
                1, Long.MAX_VALUE, Long.MAX_VALUE, 1));
        assertFalse(AelisSimulationSinglePattern.canReplayWithin(
                Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE - 1));
    }

    @Test
    void reportsEstimatedReplayStepsForComplexityGuard() {
        assertEquals(4, AelisSimulationSinglePattern.requiredReplaySteps(
                2, 12, 6));
        assertEquals(Long.MAX_VALUE,
                AelisSimulationSinglePattern.requiredReplaySteps(3, 1, 2));
        assertEquals(0, AelisSimulationSinglePattern.requiredReplaySteps(
                1, 0, 1));
    }
}
