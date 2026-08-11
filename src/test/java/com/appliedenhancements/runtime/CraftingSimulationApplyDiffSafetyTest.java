package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CraftingSimulationApplyDiffSafetyTest {
    @Test
    void parentDifferencePlusChildRequirementRejectsOverflow() {
        long parentDifference = checkedNonNegativeDifference(Long.MAX_VALUE, 0);
        assertEquals(Long.MAX_VALUE, parentDifference);
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.addExact(
                        parentDifference, 1, "required extraction diff"));
    }

    @Test
    void negativeParentDifferenceCanBeOffsetWithoutFalsePositive() {
        long parentDifference = checkedNonNegativeDifference(0, Long.MAX_VALUE);
        assertEquals(-Long.MAX_VALUE, parentDifference);
        assertEquals(0, NativeCraftingLongSafety.addExact(
                parentDifference, Long.MAX_VALUE, "required extraction diff"));
    }

    @Test
    void nonNegativeCountersCannotProduceLongMinSizeDelta() {
        long mostNegativeSizeDelta = checkedNonNegativeDifference(0, Long.MAX_VALUE);
        assertEquals(-Long.MAX_VALUE, mostNegativeSizeDelta);
        assertEquals(Long.MAX_VALUE, -mostNegativeSizeDelta);
    }

    @Test
    void corruptedNegativeCounterIsRejectedBeforeNegation() {
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> checkedNonNegativeDifference(0, Long.MIN_VALUE));
    }

    private static long checkedNonNegativeDifference(long left, long right) {
        NativeCraftingLongSafety.addNonNegative(left, 0, "left simulated counter");
        NativeCraftingLongSafety.addNonNegative(right, 0, "right simulated counter");
        return NativeCraftingLongSafety.addExact(
                left, -right, "simulated counter difference");
    }
}
