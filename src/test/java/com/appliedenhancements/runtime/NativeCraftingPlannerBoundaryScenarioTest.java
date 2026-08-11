package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NativeCraftingPlannerBoundaryScenarioTest {
    @Test
    void inventorySatisfiedLongMaxRequestHasZeroRemainingNodeDemand() {
        long requested = Long.MAX_VALUE;
        long extractedFromInventory = Long.MAX_VALUE;
        long remaining = requested - extractedFromInventory;

        assertEquals(0, remaining);
        assertEquals(0, NativeCraftingLongSafety.multiplyNonNegative(
                2, remaining, "remaining crafting tree node demand"));
    }

    @Test
    void remainingLongMaxDemandForTwoUnitNodeIsRejectedBeforeRecipeMutation() {
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        2, Long.MAX_VALUE, "remaining crafting tree node demand"));
    }

    @Test
    void longMaxRequestForTwoOutputRecipeHasNoRepresentableProducedTotal() {
        long largestSafeEvenRequest = Long.MAX_VALUE - 1;
        long safeRepetitions = NativeCraftingLongSafety.ceilDivPositive(
                largestSafeEvenRequest, 2, "two-output pattern repetitions");
        assertEquals(largestSafeEvenRequest,
                NativeCraftingLongSafety.multiplyNonNegative(
                        2, safeRepetitions, "two-output pattern total"));

        long overflowingRepetitions = NativeCraftingLongSafety.ceilDivPositive(
                Long.MAX_VALUE, 2, "two-output pattern repetitions");
        assertEquals(4_611_686_018_427_387_904L, overflowingRepetitions);
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        2, overflowingRepetitions, "two-output pattern total"));
    }

    @Test
    void missingOrOverflowingMatchingOutputTotalsAreControlledRejections() {
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.requirePositive(
                        0, "matching pattern output count"));
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.addNonNegative(
                        Long.MAX_VALUE, 1, "matching pattern output total"));
    }
}
