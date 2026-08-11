package com.github.appliedenhancements.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CraftingCalculationProgressSnapshotTest {
    @Test
    void idleSnapshotIsStableAndInactive() {
        assertSame(CraftingCalculationProgressSnapshot.idle(), CraftingCalculationProgressSnapshot.idle());
        assertFalse(CraftingCalculationProgressSnapshot.idle().active());
        assertFalse(CraftingCalculationProgressSnapshot.idle().hasKnownTotal());
    }

    @Test
    void zeroIsAValidKnownTotal() {
        var snapshot = snapshot(0, 0);
        assertTrue(snapshot.hasKnownTotal());
    }

    @Test
    void rejectsCompletedUnitsBeyondTotal() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(2, 1));
    }

    @Test
    void rejectsTotalsBelowUnknownSentinel() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, -2));
    }

    private static CraftingCalculationProgressSnapshot snapshot(long completed, long total) {
        return new CraftingCalculationProgressSnapshot(
                1,
                1,
                CraftingCalculationProgressPhase.MAX_FAST_EXECUTING,
                OmniCalculationPath.MAX_FAST,
                0,
                0,
                completed,
                total,
                0,
                1,
                false);
    }
}
