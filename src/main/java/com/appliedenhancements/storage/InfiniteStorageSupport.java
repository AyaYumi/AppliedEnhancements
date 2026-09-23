package com.appliedenhancements.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.StorageCell;
import appeng.crafting.inv.ICraftingInventory;
import appeng.me.storage.DriveWatcher;
import com.appliedenhancements.Config;
import com.appliedenhancements.mixin.AelisChildSimulationStateAccessor;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/** World access is confined to snapshot creation on the server thread. */
public final class InfiniteStorageSupport {
    private record Probe(IActionSource source, Set<AEKey> keys) {}
    private static final ThreadLocal<Probe> PROBE = new ThreadLocal<>();

    private InfiniteStorageSupport() {}

    public static boolean isInfinite(MEStorage storage) {
        // Drives and ME chests both wrap cells; inspect identity only, and always probe
        // extraction through the original outer wrapper so its restrictions remain active.
        for (int depth = 0; storage != null && depth < 32; depth++) {
            if (storage instanceof StorageCell cell) return InfiniteStorageCellRegistry.isInfinite(cell);
            if (storage instanceof DriveWatcher drive) return InfiniteStorageCellRegistry.isInfinite(drive.getCell());
            if (!(storage instanceof com.appliedenhancements.mixin.DelegatingMEInventoryAccessor wrapper)) return false;
            storage = wrapper.appliedenhancements$getDelegate();
        }
        return false;
    }

    public static Set<AEKey> snapshot(MEStorage storage, IActionSource source) {
        if (!Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get()) return Set.of();
        Probe previous = PROBE.get();
        Probe probe = new Probe(source == null ? IActionSource.empty() : source, new HashSet<>());
        PROBE.set(probe);
        try {
            storage.getAvailableStacks(new KeyCounter());
            return Set.copyOf(probe.keys());
        } finally {
            if (previous == null) PROBE.remove(); else PROBE.set(previous);
        }
    }

    public static void observe(MEStorage storage, KeyCounter contents) {
        Probe probe = PROBE.get();
        if (probe == null || !isInfinite(storage)) return;
        for (var entry : contents) {
            // Go through the mounted wrapper, preserving extraction filters and source checks.
            if (entry.getLongValue() > 0
                    && storage.extract(entry.getKey(), 1, Actionable.SIMULATE, probe.source()) > 0) {
                probe.keys().add(entry.getKey());
            }
        }
    }

    public static boolean isInfinite(ICraftingInventory inventory, AEKey key) {
        ICraftingInventory current = inventory;
        while (current instanceof InfinitePlanningInventory infinite) {
            if (infinite.appliedenhancements$ignoredInfiniteKeys().contains(key)) return false;
            if (infinite.appliedenhancements$infiniteKeys().contains(key)) return true;
            if (!(current instanceof AelisChildSimulationStateAccessor child)) break;
            current = child.appliedenhancements$getParent();
        }
        return false;
    }

    public static boolean consume(ICraftingInventory inventory, AEKey key, BigInteger amount) {
        if (!isInfinite(inventory, key)) return false;
        ((InfinitePlanningInventory) inventory).appliedenhancements$useInfinite(key, amount);
        return true;
    }
}
