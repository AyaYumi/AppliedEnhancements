package com.github.appliedenhancements.integration.ae2;

public interface CraftingCalculationProgressMenuBridge {
    CraftingCalculationProgressSnapshot molecularmanipulator$getCalculationProgress();

    boolean molecularmanipulator$acceptCalculationProgress(
            CraftingCalculationProgressSnapshot progress);

    /** Clears client presentation state without applying network snapshot ordering. */
    default void molecularmanipulator$resetCalculationProgress() {
        // Compatibility default for third-party bridge implementations compiled
        // before reset support was added. CraftConfirmMenu overrides this.
    }
}
