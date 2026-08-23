package com.appliedenhancements.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import com.appliedenhancements.api.InfiniteStorageCellMarker;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class InfiniteStorageCellRegistryTest {
    @Test
    void recognizesOnlyExactBuiltInStorageImplementations() {
        assertTrue(InfiniteStorageCellRegistry.isBuiltInStorageClass(
                "appeng.me.cells.CreativeCellInventory"));
        assertTrue(InfiniteStorageCellRegistry.isBuiltInStorageClass(
                "com.glodblock.github.extendedae.common.inventory.InfinityCellInventory"));
        assertFalse(InfiniteStorageCellRegistry.isBuiltInStorageClass(
                "example.CreativeCellInventory"));
        assertFalse(InfiniteStorageCellRegistry.isBuiltInStorageClass(
                "com.glodblock.github.extendedae.common.inventory.VoidCellInventory"));
    }

    @Test
    void recognizesPublicRuntimeMarker() {
        assertTrue(InfiniteStorageCellRegistry.isInfinite(new MarkedCell()));
        assertFalse(InfiniteStorageCellRegistry.isInfinite(new TestCell()));
    }

    private static class TestCell implements StorageCell {
        @Override
        public CellState getStatus() {
            return CellState.EMPTY;
        }

        @Override
        public double getIdleDrain() {
            return 0;
        }

        @Override
        public void persist() {
        }

        @Override
        public Component getDescription() {
            return Component.empty();
        }
    }

    private static final class MarkedCell extends TestCell
            implements InfiniteStorageCellMarker {
    }
}
