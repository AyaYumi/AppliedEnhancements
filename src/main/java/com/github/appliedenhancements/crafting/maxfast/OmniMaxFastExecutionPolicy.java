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
            boolean hasSubstituteInputs, boolean hasReusableInputs) {
        if (contextSensitive || hasSubstituteInputs || hasReusableInputs) {
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
        return nodeAmount > 0;
    }

    static boolean mayBatchDeterministicDamageSubstitute(
            String barrierReason,
            boolean deterministicDamage,
            long multiplier,
            long selectedAmount) {
        return ("recursive_durability_input".equals(barrierReason)
                || "fuzzy_crafted_input".equals(barrierReason))
                && deterministicDamage
                && multiplier > 0
                && selectedAmount == 1;
    }

    static boolean mayExecuteNativeBoundary(
            long nodeAmount, long requestMultipliers, long maxLinearItems) {
        if (nodeAmount <= 0 || requestMultipliers <= 0 || maxLinearItems < 0) {
            return false;
        }
        long total = nodeAmount > Long.MAX_VALUE / requestMultipliers
                ? Long.MAX_VALUE
                : nodeAmount * requestMultipliers;
        return total <= maxLinearItems;
    }

    /**
     * The sparse solver models each candidate's output count explicitly, so a
     * complete exact candidate set does not need equal per-pattern outputs.
     * Requiring equality here would force high-throughput alternatives back
     * into AE2's per-item ordered loop even though the compact inventory model
     * preserves their order, surplus and shared descendant capacity.
     */
    static boolean mayModelSparseOrderedCandidateSet(boolean allCandidatesCompiled,
            int compiledCandidates, int totalCandidates,
            boolean allLocalShapesExact) {
        return allCandidatesCompiled
                && compiledCandidates > 1
                && compiledCandidates == totalCandidates
                && allLocalShapesExact;
    }

    /**
     * AE2 can prune a recursion-blocked child process after the compiler has
     * interned the corresponding graph node. During simulation that live
     * occurrence is semantically a terminal input: stock is consumed first and
     * the remainder is reported missing. Only the first-candidate sparse model
     * may make this occurrence-local substitution.
     */
    static boolean mayTreatMissingCompiledSimulationCandidateAsTerminal(
            boolean simulationFirstCandidate, boolean graphEmitter,
            boolean hasCompiledCandidates, boolean liveEmitter,
            boolean liveProcessesKnown, boolean liveProcessesEmpty) {
        return simulationFirstCandidate
                && !graphEmitter
                && !hasCompiledCandidates
                && !liveEmitter
                && liveProcessesKnown
                && liveProcessesEmpty;
    }

    /**
     * Selects how many compiled candidates may be replayed transactionally.
     *
     * <p>Real execution may probe every candidate only when the complete
     * ordered choice is deterministic. If later candidates make the
     * choice non-deterministic, a separately certified first candidate may
     * still be tried once: success preserves AE2's first-candidate priority,
     * while failure must fall through directly to one native request.</p>
     */
    static int compiledCandidateTrialLimit(
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
        if (deterministicCandidates) {
            return compiledCandidates;
        }
        return firstCandidateBatchValid ? 1 : 0;
    }

    static boolean canBatchCandidateMix(
            boolean simulation, boolean allCandidatesCompiled,
            int compiledCandidates, int totalCandidates,
            boolean safeCandidateMix) {
        return !simulation
                && allCandidatesCompiled
                && compiledCandidates > 0
                && compiledCandidates == totalCandidates
                && safeCandidateMix;
    }

    /**
     * AE2's {@code possible} bit is mutable trial state. Simulation
     * may temporarily re-enable a process only after the compiler has proven
     * that its complete, stateless deterministic shape is unchanged.
     */
    static boolean mayRecoverSimulationCandidateState(
            boolean simulation, boolean deterministicPattern,
            boolean exactPrimaryOutputs, boolean statelessCandidate,
            boolean structuralMatch) {
        return simulation
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
            boolean simulation, boolean exactRequest,
            boolean allCandidatesCompiled, int compiledCandidates,
            int totalCandidates, boolean liveCandidateSet) {
        return !simulation
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
