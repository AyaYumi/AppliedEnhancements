package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeCraftingLongSafetyTest {
    @Test
    void longMaxRequestIsAllowedWhenRecipeArithmeticIsActuallySafe() {
        assertEquals(Long.MAX_VALUE, NativeCraftingLongSafety.multiplyNonNegative(
                Long.MAX_VALUE, 1, "one-to-one recipe"));
        assertEquals(Long.MAX_VALUE, NativeCraftingLongSafety.addNonNegative(
                Long.MAX_VALUE, 0, "unchanged total"));
    }

    @Test
    void outputOfTwoRejectsFirstAmountThatWouldOverflow() {
        long largestSafeRequest = Long.MAX_VALUE / 2;
        assertEquals(largestSafeRequest * 2, NativeCraftingLongSafety.multiplyNonNegative(
                largestSafeRequest, 2, "two-output recipe"));

        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        largestSafeRequest + 1, 2, "two-output recipe"));
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        Long.MAX_VALUE, 2, "two-output recipe"));
    }

    @Test
    void cumulativeRecipeCountsUseExactAddition() {
        assertEquals(Long.MAX_VALUE, NativeCraftingLongSafety.addNonNegative(
                Long.MAX_VALUE - 1, 1, "pattern total"));
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.addNonNegative(
                        Long.MAX_VALUE, 1, "pattern total"));
    }

    @Test
    void safeCeilDivisionAvoidsAe2IntermediateAdditionOverflow() {
        assertEquals(4_611_686_018_427_387_904L,
                NativeCraftingLongSafety.ceilDivPositive(
                        Long.MAX_VALUE, 2, "pattern repetitions"));
        assertEquals(Long.MAX_VALUE,
                NativeCraftingLongSafety.ceilDivPositive(
                        Long.MAX_VALUE, 1, "pattern repetitions"));
    }

    @Test
    void nonPositiveOverflowedPatternCountIsRejected() {
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.requirePositive(-1, "pattern craft count"));
        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.requirePositive(0, "pattern craft count"));
    }

    @Test
    void configuredMaximumRemainsInclusiveAndIndependentOfRecipeSafety() {
        long configuredMaximum = 1_000_000_000_000L;
        assertFalse(NativeCraftingLongSafety.exceedsConfiguredLimit(
                configuredMaximum, configuredMaximum));
        assertTrue(NativeCraftingLongSafety.exceedsConfiguredLimit(
                configuredMaximum + 1, configuredMaximum));
    }

    @Test
    void unsafeArithmeticCanBeRecognizedThroughAe2FutureWrappers() {
        var unsafe = assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        Long.MAX_VALUE, 2, "wrapped failure"));
        assertTrue(NativeCraftingLongSafety.causedByUnsafeArithmetic(
                new RuntimeException(new java.util.concurrent.ExecutionException(unsafe))));
    }
}
