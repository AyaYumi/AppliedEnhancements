package com.appliedenhancements.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InfiniteStorageAmountsTest {
    @Test
    void saturatesInfiniteAndOverflowingNetworkTotals() {
        assertEquals(30, InfiniteStorageAmounts.mergeAvailable(10, 20, false));
        assertEquals(Long.MAX_VALUE, InfiniteStorageAmounts.mergeAvailable(10, 20, true));
        assertEquals(InfiniteStorageAmounts.MAX_FINITE_AMOUNT,
                InfiniteStorageAmounts.mergeAvailable(Long.MAX_VALUE - 5, 10, false));
        assertEquals(InfiniteStorageAmounts.MAX_FINITE_AMOUNT,
                InfiniteStorageAmounts.mergeAvailable(0, Long.MAX_VALUE, false));
        assertEquals(Long.MAX_VALUE,
                InfiniteStorageAmounts.mergeAvailable(Long.MAX_VALUE, 1, false));
    }

    @Test
    void rejectsInvalidAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> InfiniteStorageAmounts.mergeAvailable(-1, 1, false));
    }
}
