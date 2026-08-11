package com.github.appliedenhancements.crafting.maxfast;

final class OmniMaxFastExecutionPolicy {
    private static final long SLOW_NATIVE_BOUNDARY_NANOS = 1_000_000_000L;

    enum Scope {
        PURE_TOPOLOGICAL,
        TOPOLOGICAL_WITH_LOCAL_BOUNDARIES,
        CONTEXTUAL_TRANSACTIONAL
    }

    enum BoundaryStrategy {
        NATIVE,
        COMPILED_CANDIDATES_THEN_NATIVE
    }

    private OmniMaxFastExecutionPolicy() {
    }

    static Scope select(boolean contextSensitive, boolean hasLocalBoundaries,
            boolean hasSubstituteInputs) {
        if (contextSensitive || hasSubstituteInputs) {
            return Scope.CONTEXTUAL_TRANSACTIONAL;
        }
        return hasLocalBoundaries
                ? Scope.TOPOLOGICAL_WITH_LOCAL_BOUNDARIES
                : Scope.PURE_TOPOLOGICAL;
    }

    static BoundaryStrategy selectBoundary(String reason) {
        return "ordered_pattern_choices".equals(reason)
                ? BoundaryStrategy.COMPILED_CANDIDATES_THEN_NATIVE
                : BoundaryStrategy.NATIVE;
    }

    static boolean isOrderedCandidateChoice(
            String barrierReason, int candidateCount) {
        return candidateCount > 1
                || "ordered_pattern_choices".equals(barrierReason);
    }

    static boolean supportsDirectStockOutputMix(long nodeAmount) {
        return nodeAmount == 1;
    }

    /**
     * Selects how many compiled candidates may be replayed transactionally.
     *
     * <p>Real aggressive execution may probe every candidate only when the
     * complete ordered choice is deterministic. If later candidates make the
     * choice non-deterministic, a separately certified first candidate may
     * still be tried once: success preserves AE2's first-candidate priority,
     * while failure must fall through directly to one native request.</p>
     */
    static int compiledCandidateTrialLimit(OmniMaxFastMode mode,
            boolean simulation, boolean deterministicCandidates,
            boolean firstCandidateBatchValid, int compiledCandidates) {
        if (compiledCandidates <= 0) {
            return 0;
        }
        if (simulation) {
            // The broad whole-set certificate is sufficient for real sparse
            // capacity probing, but it deliberately admits isolated native
            // leaves. A simulated aggregate may be committed only under the
            // stricter first-candidate batch certificate.
            return firstCandidateBatchValid ? 1 : 0;
        }
        if (mode != OmniMaxFastMode.AGGRESSIVE) {
            return 1;
        }
        if (deterministicCandidates) {
            return compiledCandidates;
        }
        return firstCandidateBatchValid ? 1 : 0;
    }

    static boolean canBatchCandidateMix(OmniMaxFastMode mode,
            boolean simulation, boolean allCandidatesCompiled,
            int compiledCandidates, int totalCandidates,
            boolean safeCandidateMix) {
        return mode == OmniMaxFastMode.AGGRESSIVE
                && !simulation
                && allCandidatesCompiled
                && compiledCandidates > 0
                && compiledCandidates == totalCandidates
                && safeCandidateMix;
    }

    /**
     * AE2's {@code possible} bit is mutable trial state. AGGRESSIVE simulation
     * may temporarily re-enable a process only after the compiler has proven
     * that its complete, stateless deterministic shape is unchanged. SAFE mode
     * continues to treat the bit as authoritative.
     */
    static boolean mayRecoverSimulationCandidateState(OmniMaxFastMode mode,
            boolean simulation, boolean deterministicPattern,
            boolean exactPrimaryOutputs, boolean statelessCandidate,
            boolean structuralMatch) {
        return mode == OmniMaxFastMode.AGGRESSIVE
                && simulation
                && deterministicPattern
                && exactPrimaryOutputs
                && statelessCandidate
                && structuralMatch;
    }

    /**
     * After candidate zero reports a real shortage, try the exact sparse
     * capacity model before committing a successful prefix and replaying the
     * remainder natively. The sparse planner itself remains the final proof;
     * this method only gates when that proof may be attempted.
     */
    static boolean shouldTrySparseCandidateSetAfterFirstShortage(
            OmniMaxFastMode mode, boolean simulation, boolean exactRequest,
            boolean allCandidatesCompiled, int compiledCandidates,
            int totalCandidates, boolean liveCandidateSet) {
        return mode == OmniMaxFastMode.AGGRESSIVE
                && !simulation
                && exactRequest
                && allCandidatesCompiled
                && compiledCandidates > 1
                && compiledCandidates == totalCandidates
                && liveCandidateSet;
    }

    static boolean shouldLogNativeBoundary(boolean diagnostics,
            boolean completed, long elapsedNanos) {
        return diagnostics
                || !completed
                || elapsedNanos >= SLOW_NATIVE_BOUNDARY_NANOS;
    }

    static boolean isBinarySearchCompatibleNativeLeaf(String reason) {
        return "secondary_or_fuzzy_output".equals(reason)
                || "quantity_limited_pattern".equals(reason);
    }

    static long upperMidpoint(long lowerInclusive, long upperInclusive) {
        long distance = upperInclusive - lowerInclusive;
        return lowerInclusive + distance / 2 + distance % 2;
    }
}
