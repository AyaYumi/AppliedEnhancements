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
import java.util.HashSet;
import java.util.Set;

/** World access is confined to snapshot creation on the server thread. */
public final class InfiniteStorageSupport {
    private record Probe(IActionSource source, Set<AEKey> keys) {}
    private static final ThreadLocal<Probe> PROBE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PHYSICAL_EXTRACT = new ThreadLocal<>();

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

    public static void observe(MEStorage storage, KeyCounter contents, boolean infiniteStorage) {
        Probe probe = PROBE.get();
        if (probe == null || !infiniteStorage) return;
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
