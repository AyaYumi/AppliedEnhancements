package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OmniSimulationSinglePatternTest {
    @Test
    void acceptsOneRequestedUnitFromOneOutput() {
        assertTrue(OmniSimulationSinglePattern.isAtMostOnePattern(1, 1, 1));
    }

    @Test
    void acceptsRequestCoveredByOneLargerOutput() {
        assertTrue(OmniSimulationSinglePattern.isAtMostOnePattern(2, 3, 6));
    }

    @Test
    void rejectsASecondRequiredPattern() {
        assertFalse(OmniSimulationSinglePattern.isAtMostOnePattern(1, 2, 1));
        assertFalse(OmniSimulationSinglePattern.isAtMostOnePattern(3, 2, 5));
    }

    @Test
    void rejectsInvalidAndOverflowingVolumes() {
        assertFalse(OmniSimulationSinglePattern.isAtMostOnePattern(0, 1, 1));
        assertFalse(OmniSimulationSinglePattern.isAtMostOnePattern(1, 0, 1));
        assertFalse(OmniSimulationSinglePattern.isAtMostOnePattern(1, 1, 0));
        assertFalse(OmniSimulationSinglePattern.isAtMostOnePattern(
                Long.MAX_VALUE, 2, Long.MAX_VALUE));
    }

    @Test
    void acceptsLargestNonOverflowingSinglePatternRequest() {
        assertTrue(OmniSimulationSinglePattern.isAtMostOnePattern(
                Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertTrue(OmniSimulationSinglePattern.isAtMostOnePattern(
                1, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void derivesLargestOnePatternReplayChunk() {
        assertEquals(1,
                OmniSimulationSinglePattern
                        .maxRequestMultipliersPerPattern(1, 1));
        assertEquals(3,
                OmniSimulationSinglePattern
                        .maxRequestMultipliersPerPattern(2, 6));
        assertEquals(0,
                OmniSimulationSinglePattern
                        .maxRequestMultipliersPerPattern(3, 2));
    }

    @Test
    void boundsLinearReplayByPatternSteps() {
        assertTrue(OmniSimulationSinglePattern.canReplayWithin(
                1, 8, 1, 8));
        assertTrue(OmniSimulationSinglePattern.canReplayWithin(
                2, 12, 6, 4));
        assertFalse(OmniSimulationSinglePattern.canReplayWithin(
                1, 9, 1, 8));
        assertFalse(OmniSimulationSinglePattern.canReplayWithin(
                3, 1, 2, 8));
        assertFalse(OmniSimulationSinglePattern.canReplayWithin(
                1, 1, 1, 0));
    }

    @Test
    void replayStepCalculationDoesNotOverflow() {
        assertTrue(OmniSimulationSinglePattern.canReplayWithin(
                1, Long.MAX_VALUE, Long.MAX_VALUE, 1));
        assertFalse(OmniSimulationSinglePattern.canReplayWithin(
                Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE - 1));
    }

    @Test
    void reportsEstimatedReplayStepsForComplexityGuard() {
        assertEquals(4, OmniSimulationSinglePattern.requiredReplaySteps(
                2, 12, 6));
        assertEquals(Long.MAX_VALUE,
                OmniSimulationSinglePattern.requiredReplaySteps(3, 1, 2));
        assertEquals(0, OmniSimulationSinglePattern.requiredReplaySteps(
                1, 0, 1));
    }
}
