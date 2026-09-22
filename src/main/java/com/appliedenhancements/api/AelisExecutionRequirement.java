package com.appliedenhancements.api;

/**
 * Fields exceeding long, plus an existing saturated-projection marker.
 * This describes quantities, not CPU compatibility, recipe validity or material availability.
 */
public record AelisExecutionRequirement(
        boolean finalOutput,
        boolean patternTimes,
        boolean storedAmounts,
        boolean infiniteInputs,
        boolean bytes,
        boolean craftedAmounts,
        boolean missingAmounts,
        boolean projectionSaturated) {
    public boolean requiresExactExecution() {
        return finalOutput || patternTimes || storedAmounts || infiniteInputs || craftedAmounts || projectionSaturated;
    }

    /** Includes display/accounting fields that alone do not imply exact CPU execution. */
    public boolean requiresExactMetadata() {
        return requiresExactExecution() || bytes || missingAmounts;
    }
}
