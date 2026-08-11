package com.appliedenhancements.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InfiniteStorageAmountsTest {
    @Test
    void probesTheLargestUnitAlignedAmount() {
        assertEquals(Long.MAX_VALUE, InfiniteStorageAmounts.probeAmount(100, 1));
        assertEquals(9_223_372_036_854_775_000L,
                InfiniteStorageAmounts.probeAmount(2_147_483_647_000L, 1_000));
        assertEquals(Long.MAX_VALUE, InfiniteStorageAmounts.probeAmount(Long.MAX_VALUE - 10, 1_000));
    }

    @Test
    void detectsProvidersThatSatisfyMoreThanTheyAdvertise() {
        assertTrue(InfiniteStorageAmounts.isInfiniteResponse(100, Long.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(InfiniteStorageAmounts.isInfiniteResponse(Long.MAX_VALUE, Long.MAX_VALUE, 0));
        assertFalse(InfiniteStorageAmounts.isInfiniteResponse(100, Long.MAX_VALUE, 100));
        assertFalse(InfiniteStorageAmounts.isInfiniteResponse(0, 1, 1));
    }

    @Test
    void saturatesInfiniteAndOverflowingNetworkTotals() {
        assertEquals(30, InfiniteStorageAmounts.mergeAvailable(10, 20, false));
        assertEquals(Long.MAX_VALUE, InfiniteStorageAmounts.mergeAvailable(10, 20, true));
        assertEquals(Long.MAX_VALUE,
                InfiniteStorageAmounts.mergeAvailable(Long.MAX_VALUE - 5, 10, false));
        assertEquals(Long.MAX_VALUE,
                InfiniteStorageAmounts.mergeAvailable(Long.MAX_VALUE, 1, false));
    }

    @Test
    void rejectsInvalidAmounts() {
        assertThrows(IllegalArgumentException.class, () -> InfiniteStorageAmounts.probeAmount(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> InfiniteStorageAmounts.probeAmount(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> InfiniteStorageAmounts.mergeAvailable(-1, 1, false));
    }
}
