package com.appliedenhancements.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.api.storage.MEStorage;
import com.appliedenhancements.api.InfiniteStorageCellMarker;
import com.appliedenhancements.mixin.DelegatingMEInventoryAccessor;
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

    @Test
    void mountedClassifierTracksReplacementAndUnmount() {
        var classifier = new InfiniteStorageCellRegistry.MountedClassifier();
        var wrapper = new MutableWrapper(new TestCell());
        assertFalse(classifier.isInfinite(wrapper));

        wrapper.delegate = new MarkedCell();
        assertTrue(classifier.isInfinite(wrapper));

        wrapper.delegate = new TestCell();
        assertFalse(classifier.isInfinite(wrapper));
        classifier.forget(wrapper);
        wrapper.delegate = new MarkedCell();
        assertTrue(classifier.isInfinite(wrapper));
    }

    private static final class MutableWrapper implements MEStorage, DelegatingMEInventoryAccessor {
        private MEStorage delegate;

        private MutableWrapper(MEStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public MEStorage appliedenhancements$getDelegate() {
            return delegate;
        }

        @Override
        public Component getDescription() {
            return Component.empty();
        }
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
