package com.appliedenhancements.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CraftingStorageFormatterTest {
    @Test void formatsBeyondLongWithoutCappingAtExabytes() {
        assertEquals("102.4EB", CraftingStorageFormatter.formatBytes(new java.math.BigInteger("102400000000000000000")));
        assertEquals("1ZB", CraftingStorageFormatter.formatBytes(java.math.BigInteger.TEN.pow(21)));
        assertEquals("9.2YB", CraftingStorageFormatter.formatBytes(new java.math.BigInteger("9200000000000000000000000")));
        assertEquals("1BB", CraftingStorageFormatter.formatBytes(java.math.BigInteger.TEN.pow(27)));
    }

    @Test
    void formatsCraftingCpuBytesWithDecimalStorageUnits() {
        assertEquals("0B", CraftingStorageFormatter.formatBytes(0));
        assertEquals("999B", CraftingStorageFormatter.formatBytes(999));
        assertEquals("1KB", CraftingStorageFormatter.formatBytes(1_000));
        assertEquals("1.5KB", CraftingStorageFormatter.formatBytes(1_500));
        assertEquals("1.5MB", CraftingStorageFormatter.formatBytes(1_500_000));
        assertEquals("2.2GB", CraftingStorageFormatter.formatBytes(2_200_000_000L));
        assertEquals("9.2EB", CraftingStorageFormatter.formatBytes(Long.MAX_VALUE));
    }
}
