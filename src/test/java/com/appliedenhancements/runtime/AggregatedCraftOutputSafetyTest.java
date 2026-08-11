package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AggregatedCraftOutputSafetyTest {
    @Test
    void patternOutputIsCheckedAgainstAggregatedCraftCount() {
        long previousCrafts = Long.MAX_VALUE / 2;
        long newTotal = NativeCraftingLongSafety.addNonNegative(
                previousCrafts, 1, "pattern craft total");

        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.multiplyNonNegative(
                        2, newTotal, "aggregated pattern output total"));
    }

    @Test
    void outputsFromDifferentPatternsCannotOverflowSamePendingKey() {
        long firstPatternOutput = Long.MAX_VALUE - 1;
        long secondPatternOutput = 2;

        assertThrows(
                NativeCraftingLongSafety.UnsafeNativeCraftingRequestException.class,
                () -> NativeCraftingLongSafety.addNonNegative(
                        firstPatternOutput,
                        secondPatternOutput,
                        "gross pending pattern output"));
    }

    @Test
    void grossPendingOutputBoundaryIsInclusive() {
        assertEquals(Long.MAX_VALUE, NativeCraftingLongSafety.addNonNegative(
                Long.MAX_VALUE - 2, 2, "gross pending pattern output"));
    }
}
