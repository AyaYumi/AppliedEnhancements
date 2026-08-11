package com.appliedenhancements.runtime;

import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class CraftingProgressSnapshotOrder {
    private CraftingProgressSnapshotOrder() {
    }

    public static boolean isNewer(
            CraftingCalculationProgressSnapshot current,
            CraftingCalculationProgressSnapshot incoming) {
        return incoming.generation() > current.generation()
                || incoming.generation() == current.generation()
                && incoming.revision() > current.revision();
    }

    public static boolean startsNewGeneration(
            CraftingCalculationProgressSnapshot current,
            CraftingCalculationProgressSnapshot incoming) {
        return incoming.generation() > current.generation();
    }
}
