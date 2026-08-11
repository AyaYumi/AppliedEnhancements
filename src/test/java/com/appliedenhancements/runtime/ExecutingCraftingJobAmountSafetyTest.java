package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExecutingCraftingJobAmountSafetyTest {
    @Test
    void elapsedTimeUnitConversionBoundaryIsInclusive() {
        int amountPerUnit = 1_000;
        long largestSafeProducedAmount = Long.MAX_VALUE / amountPerUnit;

        assertEquals(largestSafeProducedAmount * amountPerUnit,
                NativeCraftingLongSafety.multiplyNonNegative(
                        largestSafeProducedAmount,
                        amountPerUnit,
                        "crafting elapsed-time output amount"));
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        largestSafeProducedAmount + 1,
                        amountPerUnit,
                        "crafting elapsed-time output amount"));
    }

    @Test
    void itemUnitDoesNotReduceLongRangeBoundary() {
        assertEquals(Long.MAX_VALUE, NativeCraftingLongSafety.multiplyNonNegative(
                Long.MAX_VALUE, 1, "crafting elapsed-time output amount"));
    }
}
