package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OmniMaxFastExecutionPolicyTest {
    @Test
    void pureGraphsUseTopologicalExecution() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.PURE_TOPOLOGICAL,
                OmniMaxFastExecutionPolicy.select(false, false, false));
    }

    @Test
    void localBoundariesDoNotForceGraphWideTransactions() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.TOPOLOGICAL_WITH_LOCAL_BOUNDARIES,
                OmniMaxFastExecutionPolicy.select(false, true, false));
    }

    @Test
    void contextSensitiveGraphsRemainTransactional() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(true, false, false));
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(true, true, false));
    }

    @Test
    void substituteInputsPreserveInventoryOrderWithTransactionalExecution() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(false, false, true));
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(false, true, true));
    }

    @Test
    void orderedChoicesTryTheCompiledCandidateBeforeNativeRecursion() {
        assertEquals(
                OmniMaxFastExecutionPolicy.BoundaryStrategy.COMPILED_CANDIDATES_THEN_NATIVE,
                OmniMaxFastExecutionPolicy.selectBoundary("ordered_pattern_choices"));
    }

    @Test
    void otherBoundariesRemainNative() {
        assertEquals(
                OmniMaxFastExecutionPolicy.BoundaryStrategy.NATIVE,
                OmniMaxFastExecutionPolicy.selectBoundary("fuzzy_crafted_input"));
        assertEquals(
                OmniMaxFastExecutionPolicy.BoundaryStrategy.NATIVE,
                        OmniMaxFastExecutionPolicy.selectBoundary(null));
    }

    @Test
    void orderedFactSurvivesAnUnknownPatternBarrierReason() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.isOrderedCandidateChoice(
                        "unknown_pattern_type:example.Pattern", 2));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.isOrderedCandidateChoice(
                        "unknown_pattern_type:example.Pattern", 1));
    }

    @Test
    void mixedDirectStockOutputsRequireSingleItemRequestUnits() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.supportsDirectStockOutputMix(1));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.supportsDirectStockOutputMix(2));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.supportsDirectStockOutputMix(0));
    }

    @Test
    void aggressiveRealDeterministicChoicesCanProbeEveryCompiledCandidate() {
        assertEquals(
                4,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, false,
                        true, true, 4));
    }

    @Test
    void aggressiveRealNondeterministicChoiceOnlyTriesCertifiedFirstCandidate() {
        assertEquals(
                1,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, false,
                        false, true, 4));
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, false,
                        false, false, 4));
    }

    @Test
    void incompleteCandidateSetIsNotTreatedAsWholeSetDeterministic() {
        // The caller derives overallDeterministic only from a complete set.
        // Supplying false here locks the resulting first-only policy even when
        // the compiled prefix itself happens to be deterministic.
        assertEquals(
                1,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, false,
                        false, true, 2));
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, false,
                        false, false, 2));
    }

    @Test
    void simulationRequiresACompiledCommitSafetyCertificate() {
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, true,
                        true, false, 4));
        assertEquals(
                1,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, true,
                        false, true, 4));
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, true,
                        false, false, 4));
    }

    @Test
    void safeModeRealAttemptPreservesFirstCandidateOrdering() {
        assertEquals(
                1,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.SAFE, false,
                        false, false, 4));
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        OmniMaxFastMode.AGGRESSIVE, false,
                        true, true, 0));
    }

    @Test
    void aggressiveRealAttemptsCanBatchACompleteCandidateSet() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        OmniMaxFastMode.AGGRESSIVE, false, true,
                        2, 2, true));
    }

    @Test
    void incompleteOrNonAggressiveCandidateSetsRemainNative() {
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        OmniMaxFastMode.AGGRESSIVE, false, false,
                        2, 3, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        OmniMaxFastMode.AGGRESSIVE, true, true,
                        2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        OmniMaxFastMode.SAFE, false, true,
                        2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        OmniMaxFastMode.AGGRESSIVE, false, true,
                        2, 2, false));
    }

    @Test
    void aggressiveSimulationCanRecoverOnlyAProvenTransientCandidateState() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.mayRecoverSimulationCandidateState(
                        OmniMaxFastMode.AGGRESSIVE, true,
                        true, true, true, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayRecoverSimulationCandidateState(
                        OmniMaxFastMode.SAFE, true,
                        true, true, true, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayRecoverSimulationCandidateState(
                        OmniMaxFastMode.AGGRESSIVE, true,
                        true, true, false, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayRecoverSimulationCandidateState(
                        OmniMaxFastMode.AGGRESSIVE, true,
                        true, true, true, false));
    }

    @Test
    void completeLiveAggressiveCandidateSetGetsSparseShortagePlanning() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                OmniMaxFastMode.AGGRESSIVE, false, true,
                                true, 2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                OmniMaxFastMode.AGGRESSIVE, true, true,
                                true, 2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                OmniMaxFastMode.AGGRESSIVE, false, true,
                                false, 1, 2, true));
    }

    @Test
    void diagnosticsLogEveryNativeBoundary() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.shouldLogNativeBoundary(
                        true, true, 0));
    }

    @Test
    void quietModeOnlyLogsSlowOrIncompleteNativeBoundaries() {
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.shouldLogNativeBoundary(
                        false, true, 999_999_999L));
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.shouldLogNativeBoundary(
                        false, true, 1_000_000_000L));
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.shouldLogNativeBoundary(
                        false, false, 0));
    }

    @Test
    void isolatedMonotonicNativeLeavesAllowBinaryAllocation() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "secondary_or_fuzzy_output"));
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "quantity_limited_pattern"));
    }

    @Test
    void statefulAndDynamicNativeLeavesRejectBinaryAllocation() {
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "quantity_feedback_descendant_unsafe"));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "container_items"));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "recursive_durability_input"));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "fuzzy_crafted_input_amount"));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "dynamic_input_layout"));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.isBinarySearchCompatibleNativeLeaf(null));
    }

    @Test
    void binaryAllocationMidpointDoesNotOverflow() {
        assertEquals(5, OmniMaxFastExecutionPolicy.upperMidpoint(0, 10));
        assertEquals(5, OmniMaxFastExecutionPolicy.upperMidpoint(0, 9));
        assertEquals(
                4_611_686_018_427_387_904L,
                OmniMaxFastExecutionPolicy.upperMidpoint(0, Long.MAX_VALUE));
        assertEquals(
                Long.MAX_VALUE,
                OmniMaxFastExecutionPolicy.upperMidpoint(
                        Long.MAX_VALUE - 1, Long.MAX_VALUE));
    }
}
