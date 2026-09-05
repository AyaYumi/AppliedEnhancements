package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AelisExecutionPolicyTest {
    @Test
    void pureGraphsUseTopologicalExecution() {
        assertEquals(
                AelisExecutionPolicy.Scope.PURE_TOPOLOGICAL,
                AelisExecutionPolicy.select(false, false, false, false));
    }

    @Test
    void localBoundariesDoNotForceGraphWideTransactions() {
        assertEquals(
                AelisExecutionPolicy.Scope.TOPOLOGICAL_WITH_LOCAL_BOUNDARIES,
                AelisExecutionPolicy.select(false, true, false, false));
    }

    @Test
    void contextSensitiveGraphsRemainTransactional() {
        assertEquals(
                AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                AelisExecutionPolicy.select(true, false, false, false));
        assertEquals(
                AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                AelisExecutionPolicy.select(true, true, false, false));
    }

    @Test
    void substituteInputsPreserveInventoryOrderWithTransactionalExecution() {
        assertEquals(
                AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                AelisExecutionPolicy.select(false, false, true, false));
        assertEquals(
                AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                AelisExecutionPolicy.select(false, true, true, false));
    }

    @Test
    void reusableInputsPreserveSlotOrderWithTransactionalExecution() {
        assertEquals(
                AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                AelisExecutionPolicy.select(false, false, false, true));
        assertEquals(
                AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                AelisExecutionPolicy.select(false, true, false, true));
    }

    @Test
    void orderedChoicesTryTheCompiledCandidateBeforeNativeRecursion() {
        assertEquals(
                AelisExecutionPolicy.BoundaryStrategy.COMPILED_CANDIDATES_THEN_NATIVE,
                AelisExecutionPolicy.selectBoundary("ordered_pattern_choices"));
    }

    @Test
    void otherBoundariesRemainNative() {
        assertEquals(
                AelisExecutionPolicy.BoundaryStrategy.NATIVE,
                AelisExecutionPolicy.selectBoundary("fuzzy_crafted_input"));
        assertEquals(
                AelisExecutionPolicy.BoundaryStrategy.NATIVE,
                        AelisExecutionPolicy.selectBoundary(null));
    }

    @Test
    void orderedFactSurvivesAnUnknownPatternBarrierReason() {
        assertEquals(
                true,
                AelisExecutionPolicy.isOrderedCandidateChoice(
                        "unknown_pattern_type:example.Pattern", 2));
        assertEquals(
                false,
                AelisExecutionPolicy.isOrderedCandidateChoice(
                        "unknown_pattern_type:example.Pattern", 1));
    }

    @Test
    void mixedDirectStockOutputsAcceptAnyPositiveExactRequestUnit() {
        assertEquals(
                true,
                AelisExecutionPolicy.supportsDirectStockOutputMix(1));
        assertEquals(
                true,
                AelisExecutionPolicy.supportsDirectStockOutputMix(2));
        assertEquals(
                false,
                AelisExecutionPolicy.supportsDirectStockOutputMix(0));
    }

    @Test
    void provenDamageSubstitutesWorkForRecursiveAndFuzzyBoundaries() {
        assertEquals(
                true,
                AelisExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "recursive_durability_input", true, 2, 1));
        assertEquals(
                true,
                AelisExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "fuzzy_crafted_input", true, 1, 1));
        assertEquals(
                false,
                AelisExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "container_items", true, 1, 1));
        assertEquals(
                false,
                AelisExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "recursive_durability_input", false, 1, 1));
    }

    @Test
    void nativeBoundaryWorkLimitUsesSaturatingLogicalVolume() {
        assertEquals(
                true,
                AelisExecutionPolicy.mayExecuteNativeBoundary(
                        2, 4_096, 8_192));
        assertEquals(
                false,
                AelisExecutionPolicy.mayExecuteNativeBoundary(
                        2, 4_097, 8_192));
        assertEquals(
                false,
                AelisExecutionPolicy.mayExecuteNativeBoundary(
                        Long.MAX_VALUE, 2, 8_192));
    }

    @Test
    void sparseOrderedModelAcceptsCompleteExactCandidatesWithDifferentOutputs() {
        assertEquals(
                true,
                AelisExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        true, 2, 2, true));
        assertEquals(
                false,
                AelisExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        false, 2, 2, true));
        assertEquals(
                false,
                AelisExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        true, 1, 2, true));
        assertEquals(
                false,
                AelisExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        true, 2, 2, false));
    }

    @Test
    void simulationCanModelOnlyAPrunedNonEmittingLeafAsTerminal() {
        assertEquals(
                true,
                AelisExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                true, false, false,
                                false, true, true));
        assertEquals(
                false,
                AelisExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                false, false, false,
                                false, true, true));
        assertEquals(
                false,
                AelisExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                true, false, true,
                                false, true, true));
        assertEquals(
                false,
                AelisExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                true, false, false,
                                false, true, false));
    }

    @Test
    void realDeterministicChoicesCanProbeEveryCompiledCandidate() {
        assertEquals(
                4,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        true, true, 4));
    }

    @Test
    void realNondeterministicChoiceOnlyTriesCertifiedFirstCandidate() {
        assertEquals(
                1,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        false, true, 4));
        assertEquals(
                0,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        false, false, 4));
    }

    @Test
    void incompleteCandidateSetIsNotTreatedAsWholeSetDeterministic() {
        // The caller derives overallDeterministic only from a complete set.
        // Supplying false here locks the resulting first-only policy even when
        // the compiled prefix itself happens to be deterministic.
        assertEquals(
                1,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        false, true, 2));
        assertEquals(
                0,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        false, false, 2));
    }

    @Test
    void simulationRequiresACompiledCommitSafetyCertificate() {
        assertEquals(
                0,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        true,
                        true, false, 4));
        assertEquals(
                1,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        true,
                        false, true, 4));
        assertEquals(
                0,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        true,
                        false, false, 4));
    }

    @Test
    void zeroCompiledCandidatesCannotBeTried() {
        assertEquals(
                0,
                AelisExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        true, true, 0));
    }

    @Test
    void realAttemptsCanBatchACompleteCandidateSet() {
        assertEquals(
                true,
                AelisExecutionPolicy.canBatchCandidateMix(
                        false, true,
                        2, 2, true));
    }

    @Test
    void incompleteSimulationOrUnsafeCandidateSetsRemainNative() {
        assertEquals(
                false,
                AelisExecutionPolicy.canBatchCandidateMix(
                        false, false,
                        2, 3, true));
        assertEquals(
                false,
                AelisExecutionPolicy.canBatchCandidateMix(
                        true, true,
                        2, 2, true));
        assertEquals(
                false,
                AelisExecutionPolicy.canBatchCandidateMix(
                        false, true,
                        2, 2, false));
    }

    @Test
    void simulationCanRecoverOnlyAProvenTransientCandidateState() {
        assertEquals(
                true,
                AelisExecutionPolicy.mayRecoverSimulationCandidateState(
                        true,
                        true, true, true, true));
        assertEquals(
                false,
                AelisExecutionPolicy.mayRecoverSimulationCandidateState(
                        true,
                        true, true, false, true));
        assertEquals(
                false,
                AelisExecutionPolicy.mayRecoverSimulationCandidateState(
                        true,
                        true, true, true, false));
    }

    @Test
    void completeLiveCandidateSetGetsSparseShortagePlanning() {
        assertEquals(
                true,
                AelisExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                false, true,
                                true, 2, 2, true));
        assertEquals(
                false,
                AelisExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                true, true,
                                true, 2, 2, true));
        assertEquals(
                false,
                AelisExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                false, true,
                                false, 1, 2, true));
    }

    @Test
    void diagnosticsLogEveryNativeBoundary() {
        assertEquals(
                true,
                AelisExecutionPolicy.shouldLogNativeBoundary(
                        true, true, 0));
    }

    @Test
    void quietModeOnlyLogsSlowOrIncompleteNativeBoundaries() {
        assertEquals(
                false,
                AelisExecutionPolicy.shouldLogNativeBoundary(
                        false, true, 999_999_999L));
        assertEquals(
                true,
                AelisExecutionPolicy.shouldLogNativeBoundary(
                        false, true, 1_000_000_000L));
        assertEquals(
                true,
                AelisExecutionPolicy.shouldLogNativeBoundary(
                        false, false, 0));
    }

    @Test
    void isolatedMonotonicNativeLeavesAllowBinaryAllocation() {
        assertEquals(
                true,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "secondary_or_fuzzy_output"));
        assertEquals(
                true,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "quantity_limited_pattern"));
    }

    @Test
    void statefulAndDynamicNativeLeavesRejectBinaryAllocation() {
        assertEquals(
                false,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "quantity_feedback_descendant_unsafe"));
        assertEquals(
                false,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "container_items"));
        assertEquals(
                false,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "recursive_durability_input"));
        assertEquals(
                false,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "fuzzy_crafted_input_amount"));
        assertEquals(
                false,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(
                        "dynamic_input_layout"));
        assertEquals(
                false,
                AelisExecutionPolicy.isBinarySearchCompatibleNativeLeaf(null));
    }

    @Test
    void binaryAllocationMidpointDoesNotOverflow() {
        assertEquals(5, AelisExecutionPolicy.upperMidpoint(0, 10));
        assertEquals(5, AelisExecutionPolicy.upperMidpoint(0, 9));
        assertEquals(
                4_611_686_018_427_387_904L,
                AelisExecutionPolicy.upperMidpoint(0, Long.MAX_VALUE));
        assertEquals(
                Long.MAX_VALUE,
                AelisExecutionPolicy.upperMidpoint(
                        Long.MAX_VALUE - 1, Long.MAX_VALUE));
    }
}
