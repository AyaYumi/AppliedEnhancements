package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressPhase;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import org.junit.jupiter.api.Test;

class CraftingProgressSnapshotOrderTest {
    @Test
    void activeUpdateWithinSameGenerationDoesNotStartAnotherPlanReset() {
        var current = snapshot(9, 3, CraftingCalculationProgressPhase.QUEUED);
        var activeUpdate = snapshot(9, 4, CraftingCalculationProgressPhase.AE2_CALCULATING);

        assertTrue(CraftingProgressSnapshotOrder.isNewer(current, activeUpdate));
        assertFalse(CraftingProgressSnapshotOrder.startsNewGeneration(current, activeUpdate));
    }

    @Test
    void firstSnapshotOfNewGenerationResetsOldPlanExactlyOnce() {
        var current = snapshot(9, 7, CraftingCalculationProgressPhase.COMPLETED);
        var nextGeneration = snapshot(10, 1, CraftingCalculationProgressPhase.QUEUED);

        assertTrue(CraftingProgressSnapshotOrder.isNewer(current, nextGeneration));
        assertTrue(CraftingProgressSnapshotOrder.startsNewGeneration(current, nextGeneration));
    }

    @Test
    void duplicateAndOutOfOrderSnapshotsAreRejected() {
        var current = snapshot(9, 4, CraftingCalculationProgressPhase.AE2_CALCULATING);

        assertFalse(CraftingProgressSnapshotOrder.isNewer(
                current, snapshot(9, 4, CraftingCalculationProgressPhase.COMPLETED)));
        assertFalse(CraftingProgressSnapshotOrder.isNewer(
                current, snapshot(8, 99, CraftingCalculationProgressPhase.COMPLETED)));
    }

    private static CraftingCalculationProgressSnapshot snapshot(
            long generation, long revision, CraftingCalculationProgressPhase phase) {
        return new CraftingCalculationProgressSnapshot(
                generation,
                revision,
                phase,
                AelisCalculationPath.AE2_NATIVE,
                0,
                0,
                0,
                -1,
                0,
                0,
                false);
    }
}
