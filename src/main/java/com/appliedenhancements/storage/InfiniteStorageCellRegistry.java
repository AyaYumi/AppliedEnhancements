package com.appliedenhancements.storage;

import appeng.api.storage.cells.StorageCell;
import appeng.api.storage.MEStorage;
import com.appliedenhancements.api.InfiniteStorageCellMarker;
import com.appliedenhancements.api.InfiniteStorageCells;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Resolves explicit infinite-cell markers without probing storage behavior. */
@ApiStatus.Internal
public final class InfiniteStorageCellRegistry {
    private static final String AE2_CREATIVE_CELL_INVENTORY =
            "appeng.me.cells.CreativeCellInventory";
    private static final String EXTENDED_AE_INFINITY_CELL_INVENTORY =
            "com.glodblock.github.extendedae.common.inventory.InfinityCellInventory";

    private static final Map<StorageCell, CellItem> CELL_ITEMS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final BooleanSupplier ALWAYS_INFINITE = () -> true;

    private InfiniteStorageCellRegistry() {
    }

    public static void rememberCellItem(ItemStack stack, StorageCell storage) {
        if (storage != null && stack != null && !stack.isEmpty()) {
            cellItem(storage).stack = stack.copy();
        }
    }

    public static boolean isInfinite(StorageCell storage) {
        if (storage == null) {
            return false;
        }
        if (storage instanceof InfiniteStorageCellMarker
                || isBuiltInStorageClass(storage.getClass().getName())) {
            return true;
        }
        CellItem source = CELL_ITEMS.get(storage);
        return source != null && source.getAsBoolean();
    }

    private static BooleanSupplier classifier(StorageCell storage) {
        if (storage instanceof InfiniteStorageCellMarker
                || isBuiltInStorageClass(storage.getClass().getName())) {
            return ALWAYS_INFINITE;
        }
        return cellItem(storage);
    }

    private static CellItem cellItem(StorageCell storage) {
        synchronized (CELL_ITEMS) {
            return CELL_ITEMS.computeIfAbsent(storage, ignored -> new CellItem());
        }
    }

    private static final class CellItem implements BooleanSupplier {
        private volatile ItemStack stack;

        @Override
        public boolean getAsBoolean() {
            return InfiniteStorageCells.isMarked(stack);
        }
    }

    /** Keeps the hot extraction path independent of the weak association table. */
    public static final class MountedClassifier {
        private final Map<MEStorage, CachedCell> mounted =
                Collections.synchronizedMap(new IdentityHashMap<>());

        public boolean isInfinite(MEStorage storage) {
            StorageCell cell = InfiniteStorageSupport.mountedCell(storage);
            if (cell == null) {
                mounted.remove(storage);
                return false;
            }
            CachedCell cached = mounted.get(storage);
            if (cached == null || cached.cell() != cell) {
                cached = new CachedCell(cell, classifier(cell));
                mounted.put(storage, cached);
            }
            return cached.marked().getAsBoolean();
        }

        public void forget(MEStorage storage) {
            mounted.remove(storage);
        }

        private record CachedCell(StorageCell cell, BooleanSupplier marked) {}
    }

    static boolean isBuiltInStorageClass(String className) {
        return AE2_CREATIVE_CELL_INVENTORY.equals(className)
                || EXTENDED_AE_INFINITY_CELL_INVENTORY.equals(className);
    }
}
