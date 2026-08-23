package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InfiniteStorageCellsTest {
    @Test
    void exposesStableItemTagIdentifier() {
        assertEquals(
                "appliedenhancements:infinite_storage_cells",
                InfiniteStorageCells.ITEM_TAG.location().toString());
    }
}
