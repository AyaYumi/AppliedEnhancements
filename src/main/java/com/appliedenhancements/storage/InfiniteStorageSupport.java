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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** World access is confined to snapshot creation on the server thread. */
public final class InfiniteStorageSupport {
    private static final ThreadLocal<Boolean> PHYSICAL_EXTRACT = new ThreadLocal<>();
    private static final ThreadLocal<IdentityHashMap<MEStorage, Boolean>> AVAILABILITY_QUERIES =
            new ThreadLocal<>();

    private InfiniteStorageSupport() {}

    public static StorageCell mountedCell(MEStorage storage) {
        // Drives and ME chests both wrap cells; inspect identity only, and always probe
        // extraction through the original outer wrapper so its restrictions remain active.
        for (int depth = 0; storage != null && depth < 32; depth++) {
            if (storage instanceof StorageCell cell) return cell;
            if (storage instanceof DriveWatcher drive) return drive.getCell();
            if (!(storage instanceof com.appliedenhancements.mixin.DelegatingMEInventoryAccessor wrapper)) return null;
            storage = wrapper.appliedenhancements$getDelegate();
        }
        return null;
    }

    public static boolean isPhysicalExtract() {
        return Boolean.TRUE.equals(PHYSICAL_EXTRACT.get());
    }

    /** Rechecks sentinel-only entries against source-aware, finite storage amounts. */
    public static void reconcilePlanningSnapshot(KeyCounter list, MEStorage storage,
            IActionSource source, Set<AEKey> infiniteKeys) {
        reconcilePlanningSnapshot(list, storage, source, infiniteKeys,
                Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get());
    }

    static void reconcilePlanningSnapshot(KeyCounter list, MEStorage storage,
            IActionSource source, Set<AEKey> infiniteKeys, boolean enabled) {
        if (!enabled) return;
        var unmarkedSentinels = new ArrayList<AEKey>();
        for (var entry : list) {
            if (entry.getLongValue() == Long.MAX_VALUE
                    && !infiniteKeys.contains(entry.getKey())) {
                unmarkedSentinels.add(entry.getKey());
            }
        }
        IActionSource effectiveSource = source == null ? IActionSource.empty() : source;
        Boolean previous = PHYSICAL_EXTRACT.get();
        PHYSICAL_EXTRACT.set(true);
        try {
            for (AEKey key : unmarkedSentinels) {
                list.set(key, Math.max(0L, storage.extract(
                        key, Long.MAX_VALUE, Actionable.SIMULATE, effectiveSource)));
            }
        } finally {
            if (previous == null) PHYSICAL_EXTRACT.remove();
            else PHYSICAL_EXTRACT.set(previous);
        }
        list.removeZeros();
        for (AEKey key : infiniteKeys) list.set(key, Long.MAX_VALUE);
    }

    public static Set<AEKey> snapshot(
            MEStorage storage, IActionSource source, Iterable<AEKey> candidateKeys) {
        if (!Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get()
                || !(storage instanceof InfiniteStorageNetworkAccess access)) {
            return Set.of();
        }
        return access.appliedenhancements$snapshotInfiniteKeys(
                source == null ? IActionSource.empty() : source, candidateKeys);
    }

    public static Set<AEKey> snapshotInfiniteKeys(Iterable<? extends MEStorage> storages,
            java.util.function.Predicate<MEStorage> isInfinite,
            Iterable<AEKey> candidateKeys, IActionSource source) {
        var candidates = new LinkedHashSet<AEKey>();
        for (AEKey key : candidateKeys) {
            if (key != null) candidates.add(key);
        }
        var keys = new LinkedHashSet<AEKey>();
        for (MEStorage storage : storages) {
            if (keys.size() == candidates.size()) break;
            if (storage == null || !isInfinite.test(storage)) {
                continue;
            }

            for (AEKey key : candidates) {
                if (keys.contains(key)) continue;
                if (storage.extract(key, 1, Actionable.SIMULATE,
                        source == null ? IActionSource.empty() : source) > 0) {
                    keys.add(key);
                }
            }
        }
        return Set.copyOf(keys);
    }

    public static Map<AEKey, Long> availableAmounts(
            MEStorage storage, Iterable<AEKey> keys, IActionSource source) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (AEKey key : keys) {
            if (key != null) {
                amounts.put(key, Math.max(0L, physicalAvailable(storage, key, source)));
            }
        }
        return Map.copyOf(amounts);
    }

    public static long physicalAvailable(MEStorage storage, AEKey key, IActionSource source) {
        var queries = AVAILABILITY_QUERIES.get();
        if (queries == null) {
            queries = new IdentityHashMap<>();
            AVAILABILITY_QUERIES.set(queries);
        }
        queries.put(storage, Boolean.TRUE);
        try {
            return storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE,
                    source == null ? IActionSource.empty() : source);
        } finally {
            queries.remove(storage);
            if (queries.isEmpty()) {
                AVAILABILITY_QUERIES.remove();
            }
        }
    }

    public static boolean isAvailabilityQuery(MEStorage storage) {
        var queries = AVAILABILITY_QUERIES.get();
        return queries != null && queries.containsKey(storage);
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
