package com.appliedenhancements.storage;

import appeng.api.storage.cells.StorageCell;
import com.appliedenhancements.api.InfiniteStorageCellMarker;
import com.appliedenhancements.api.InfiniteStorageCells;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Resolves explicit infinite-cell markers without probing storage behavior. */
@ApiStatus.Internal
public final class InfiniteStorageCellRegistry {
    private static final String AE2_CREATIVE_CELL_INVENTORY =
            "appeng.me.cells.CreativeCellInventory";
    private static final String EXTENDED_AE_INFINITY_CELL_INVENTORY =
            "com.glodblock.github.extendedae.common.inventory.InfinityCellInventory";

    private static final Map<StorageCell, ItemStack> CELL_ITEMS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private InfiniteStorageCellRegistry() {
    }

    public static void rememberCellItem(ItemStack stack, StorageCell storage) {
        if (storage != null && stack != null && !stack.isEmpty()) {
            CELL_ITEMS.put(storage, stack.copy());
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
        ItemStack sourceStack = CELL_ITEMS.get(storage);
        return sourceStack != null && InfiniteStorageCells.isMarked(sourceStack);
    }

    static boolean isBuiltInStorageClass(String className) {
        return AE2_CREATIVE_CELL_INVENTORY.equals(className)
                || EXTENDED_AE_INFINITY_CELL_INVENTORY.equals(className);
    }
}
