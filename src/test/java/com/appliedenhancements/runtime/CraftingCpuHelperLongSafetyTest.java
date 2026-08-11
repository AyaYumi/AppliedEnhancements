package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CraftingCpuHelperLongSafetyTest {
    @Test
    void oneUnitTemplateCanExtractLongMaxWithoutWrapping() {
        assertEquals(Long.MAX_VALUE, NativeCraftingLongSafety.multiplyNonNegative(
                1, Long.MAX_VALUE, "input template extraction total"));
    }

    @Test
    void twoUnitTemplateRejectsLongMaxBeforeNegativeInventoryRequest() {
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        2, Long.MAX_VALUE, "input template extraction total"));
    }

    @Test
    void exactExtractionBoundaryIsInclusive() {
        long largestSafeMultiplier = Long.MAX_VALUE / 3;
        assertEquals(largestSafeMultiplier * 3,
                NativeCraftingLongSafety.multiplyNonNegative(
                        3, largestSafeMultiplier, "input template extraction total"));
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        3, largestSafeMultiplier + 1, "input template extraction total"));
    }

    @Test
    void invalidZeroTemplateAmountIsControlledBeforeAe2Division() {
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.requirePositive(
                        0, "input template amount"));
    }
}
