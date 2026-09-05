package com.github.appliedenhancements.crafting.aelis;

/**
 * Checked guard for a simulated ordered choice that can require at most one
 * execution of its first pattern.
 *
 * <p>With at most one pattern execution there is no craft-to-craft input
 * reordering to prove. The compiled transaction still performs the original
 * input order and delegates unsupported descendants to their own guarded
 * boundaries.</p>
 */
final class AelisSimulationSinglePattern {
    private AelisSimulationSinglePattern() {
    }

    static boolean isAtMostOnePattern(long nodeAmount,
            long requestMultipliers, long outputPerPattern) {
        if (nodeAmount <= 0 || requestMultipliers <= 0
                || outputPerPattern <= 0) {
            return false;
        }
        try {
            return Math.multiplyExact(nodeAmount, requestMultipliers)
                    <= outputPerPattern;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    /**
     * Returns the largest request-multiplier chunk that can be satisfied by
     * at most one execution of the selected pattern. A zero result means that
     * even one request multiplier needs multiple pattern executions.
     */
    static long maxRequestMultipliersPerPattern(
            long nodeAmount, long outputPerPattern) {
        if (nodeAmount <= 0 || outputPerPattern <= 0) {
            return 0;
        }
        return outputPerPattern / nodeAmount;
    }

    /**
     * Bounds the compatibility replay used when a whole simulated ordered
     * choice cannot be aggregated. Each replay step still executes no more
     * than one first-candidate pattern, preserving AE2's craft-by-craft order.
     */
    static boolean canReplayWithin(long nodeAmount, long requestMultipliers,
            long outputPerPattern, long maxSteps) {
        if (requestMultipliers <= 0 || maxSteps <= 0) {
            return false;
        }
        long steps = requiredReplaySteps(
                nodeAmount, requestMultipliers, outputPerPattern);
        return steps <= maxSteps;
    }

    static long requiredReplaySteps(long nodeAmount, long requestMultipliers,
            long outputPerPattern) {
        if (requestMultipliers <= 0) {
            return 0;
        }
        long chunk = maxRequestMultipliersPerPattern(
                nodeAmount, outputPerPattern);
        if (chunk <= 0) {
            return Long.MAX_VALUE;
        }
        return requestMultipliers / chunk
                + (requestMultipliers % chunk == 0 ? 0 : 1);
    }
}
