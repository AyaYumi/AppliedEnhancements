package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OmniMaxFastExecutionPolicyTest {
    @Test
    void pureGraphsUseTopologicalExecution() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.PURE_TOPOLOGICAL,
                OmniMaxFastExecutionPolicy.select(false, false, false, false));
    }

    @Test
    void localBoundariesDoNotForceGraphWideTransactions() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.TOPOLOGICAL_WITH_LOCAL_BOUNDARIES,
                OmniMaxFastExecutionPolicy.select(false, true, false, false));
    }

    @Test
    void contextSensitiveGraphsRemainTransactional() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(true, false, false, false));
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(true, true, false, false));
    }

    @Test
    void substituteInputsPreserveInventoryOrderWithTransactionalExecution() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(false, false, true, false));
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(false, true, true, false));
    }

    @Test
    void reusableInputsPreserveSlotOrderWithTransactionalExecution() {
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(false, false, false, true));
        assertEquals(
                OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL,
                OmniMaxFastExecutionPolicy.select(false, true, false, true));
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
    void mixedDirectStockOutputsAcceptAnyPositiveExactRequestUnit() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.supportsDirectStockOutputMix(1));
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.supportsDirectStockOutputMix(2));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.supportsDirectStockOutputMix(0));
    }

    @Test
    void provenDamageSubstitutesWorkForRecursiveAndFuzzyBoundaries() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "recursive_durability_input", true, 2, 1));
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "fuzzy_crafted_input", true, 1, 1));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "container_items", true, 1, 1));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayBatchDeterministicDamageSubstitute(
                        "recursive_durability_input", false, 1, 1));
    }

    @Test
    void nativeBoundaryWorkLimitUsesSaturatingLogicalVolume() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.mayExecuteNativeBoundary(
                        2, 4_096, 8_192));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayExecuteNativeBoundary(
                        2, 4_097, 8_192));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayExecuteNativeBoundary(
                        Long.MAX_VALUE, 2, 8_192));
    }

    @Test
    void sparseOrderedModelAcceptsCompleteExactCandidatesWithDifferentOutputs() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        true, 2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        false, 2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        true, 1, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayModelSparseOrderedCandidateSet(
                        true, 2, 2, false));
    }

    @Test
    void simulationCanModelOnlyAPrunedNonEmittingLeafAsTerminal() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                true, false, false,
                                false, true, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                false, false, false,
                                false, true, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                true, false, true,
                                false, true, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy
                        .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                                true, false, false,
                                false, true, false));
    }

    @Test
    void realDeterministicChoicesCanProbeEveryCompiledCandidate() {
        assertEquals(
                4,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        true, true, 4));
    }

    @Test
    void realNondeterministicChoiceOnlyTriesCertifiedFirstCandidate() {
        assertEquals(
                1,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        false, true, 4));
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
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
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        false, true, 2));
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        false, false, 2));
    }

    @Test
    void simulationRequiresACompiledCommitSafetyCertificate() {
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        true,
                        true, false, 4));
        assertEquals(
                1,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        true,
                        false, true, 4));
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        true,
                        false, false, 4));
    }

    @Test
    void zeroCompiledCandidatesCannotBeTried() {
        assertEquals(
                0,
                OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        false,
                        true, true, 0));
    }

    @Test
    void realAttemptsCanBatchACompleteCandidateSet() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        false, true,
                        2, 2, true));
    }

    @Test
    void incompleteSimulationOrUnsafeCandidateSetsRemainNative() {
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        false, false,
                        2, 3, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        true, true,
                        2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        false, true,
                        2, 2, false));
    }

    @Test
    void simulationCanRecoverOnlyAProvenTransientCandidateState() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy.mayRecoverSimulationCandidateState(
                        true,
                        true, true, true, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayRecoverSimulationCandidateState(
                        true,
                        true, true, false, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy.mayRecoverSimulationCandidateState(
                        true,
                        true, true, true, false));
    }

    @Test
    void completeLiveCandidateSetGetsSparseShortagePlanning() {
        assertEquals(
                true,
                OmniMaxFastExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                false, true,
                                true, 2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                true, true,
                                true, 2, 2, true));
        assertEquals(
                false,
                OmniMaxFastExecutionPolicy
                        .shouldTrySparseCandidateSetAfterFirstShortage(
                                false, true,
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
