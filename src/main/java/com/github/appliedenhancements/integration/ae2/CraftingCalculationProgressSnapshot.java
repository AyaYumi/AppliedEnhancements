package com.github.appliedenhancements.integration.ae2;

import java.util.Objects;

public record CraftingCalculationProgressSnapshot(
        long generation,
        long revision,
        CraftingCalculationProgressPhase phase,
        OmniCalculationPath path,
        long processedSteps,
        long discoveredNodes,
        long completedUnits,
        long totalUnits,
        long elapsedMillis,
        int attempt,
        boolean simulation) {
    private static final CraftingCalculationProgressSnapshot IDLE =
            new CraftingCalculationProgressSnapshot(
                    0, 0, CraftingCalculationProgressPhase.IDLE,
                    OmniCalculationPath.AE2_NATIVE, 0, 0, 0, -1, 0, 0, false);

    public CraftingCalculationProgressSnapshot {
        if (generation < 0 || revision < 0 || processedSteps < 0
                || discoveredNodes < 0 || completedUnits < 0
                || elapsedMillis < 0 || attempt < 0) {
            throw new IllegalArgumentException("Crafting calculation progress values must be non-negative");
        }
        if (totalUnits < -1) {
            throw new IllegalArgumentException("totalUnits must be -1 or non-negative");
        }
        if (totalUnits >= 0 && completedUnits > totalUnits) {
            throw new IllegalArgumentException("completedUnits must not exceed totalUnits");
        }
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(path, "path");
    }

    public static CraftingCalculationProgressSnapshot idle() {
        return IDLE;
    }

    public CraftingCalculationProgressSnapshot withRevision(long newRevision) {
        return new CraftingCalculationProgressSnapshot(
                generation, newRevision, phase, path, processedSteps, discoveredNodes,
                completedUnits, totalUnits, elapsedMillis, attempt, simulation);
    }

    public boolean active() {
        return phase != CraftingCalculationProgressPhase.IDLE && !phase.terminal();
    }

    public boolean hasKnownTotal() {
        return totalUnits >= 0;
    }
}
