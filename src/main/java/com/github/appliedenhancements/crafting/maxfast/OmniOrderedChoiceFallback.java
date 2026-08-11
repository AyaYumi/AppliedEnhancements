package com.github.appliedenhancements.crafting.maxfast;

/**
 * Pure policy for avoiding an unbounded AE2 ordered-choice replay after a
 * compiled attempt did not apply.
 */
final class OmniOrderedChoiceFallback {
    enum Decision {
        NATIVE,
        CONTROLLED_REJECT
    }

    private OmniOrderedChoiceFallback() {
    }

    /**
     * Returns whether a compiled result may be committed with respect to the
     * simulation-only safety gate. Real execution is still subject to the
     * planner's normal candidate certificate.
     */
    static boolean mayCommitCompiledResult(boolean simulation,
            boolean batchCertified) {
        return !simulation || batchCertified;
    }

    /**
     * Rejects native replay when the local ordered-choice work exceeds the
     * explicit bound. A small root request can fan out into millions of local
     * choices, and AE2 still pays that full per-item cost.
     */
    static Decision afterCompiledFailure(OmniMaxFastMode mode,
            long nodeAmount, long requestMultipliers,
            long rootRequestedAmount,
            long maxLinearNativeItems) {
        if (mode != OmniMaxFastMode.AGGRESSIVE
                || nodeAmount <= 0
                || requestMultipliers <= 0
                || rootRequestedAmount <= 0) {
            return Decision.NATIVE;
        }
        if (maxLinearNativeItems < 0) {
            throw new IllegalArgumentException(
                    "maxLinearNativeItems must be non-negative");
        }

        long totalItems = saturatedMultiplyPositive(
                nodeAmount, requestMultipliers);
        return totalItems > maxLinearNativeItems
                ? Decision.CONTROLLED_REJECT
                : Decision.NATIVE;
    }

    private static long saturatedMultiplyPositive(long left, long right) {
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
