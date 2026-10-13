package com.appliedenhancements.runtime;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.appliedenhancements.Config;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Reserves stored ingredients while an AELIS manual plan awaits submission. */
public final class ManualCraftingInventoryLock {
    private static final Map<MEStorage, InventoryReservationLedger<AEKey>> LEDGERS =
            new WeakHashMap<>();
    private static final ThreadLocal<Reservation> ACTIVE_SUBMISSION = new ThreadLocal<>();

    private ManualCraftingInventoryLock() {
    }

    /** The lock deliberately follows the automatic planner's existing switch. */
    public static boolean enabled() {
        return Config.ENABLE_AUTOMATIC_AELIS_PLANNER.get();
    }

    public static Reservation tryAcquire(
            MEStorage storage, KeyCounter requested, IActionSource source) {
        if (!enabled() || storage == null || requested == null) {
            return null;
        }

        Map<AEKey, Long> wanted = new LinkedHashMap<>(positiveEntries(requested));
        // An inexhaustible source must not be locked away by another confirmation menu.
        var infiniteKeys = com.appliedenhancements.storage.InfiniteStorageSupport.snapshot(
                storage, source, wanted.keySet());
        infiniteKeys.forEach(wanted::remove);
        Map<AEKey, Long> available = com.appliedenhancements.storage.InfiniteStorageSupport
                .availableAmounts(storage, wanted.keySet(), source);

        InventoryReservationLedger<AEKey> ledger;
        synchronized (LEDGERS) {
            ledger = LEDGERS.computeIfAbsent(
                    storage, ignored -> new InventoryReservationLedger<>());
        }
        var reservation = ledger.tryAcquire(available, wanted);
        return reservation == null ? null : new Reservation(storage, reservation);
    }

    /** Removes other confirmation menus' reservations from a planner snapshot. */
    public static void subtractReservations(MEStorage storage, KeyCounter available) {
        if (!enabled() || storage == null || available == null) {
            return;
        }
        InventoryReservationLedger<AEKey> ledger = ledger(storage);
        if (ledger == null) {
            return;
        }
        for (var entry : ledger.snapshot().entrySet()) {
            AEKey key = entry.getKey();
            long remaining = subtractClamped(available.get(key), entry.getValue());
            available.set(key, remaining);
        }
        available.removeZeros();
    }

    /** Limits both real and simulated extraction while retaining reserved stock. */
    public static long limitExtraction(
            MEStorage storage,
            AEKey what,
            long requested,
            Actionable mode,
            IActionSource source) {
        if (com.appliedenhancements.storage.InfiniteStorageSupport.isPhysicalExtract()
                || !enabled() || storage == null || what == null || requested <= 0) {
            return requested;
        }
        if (com.appliedenhancements.storage.InfiniteStorageSupport.isAvailabilityQuery(storage)) {
            return requested;
        }

        InventoryReservationLedger<AEKey> ledger = ledger(storage);
        if (ledger == null || ledger.isEmpty()) {
            return requested;
        }
        Reservation active = ACTIVE_SUBMISSION.get();
        var owner = active != null && active.activeFor(storage)
                ? active.delegate
                : null;
        long reservedForOthers = ledger.reservedForOthers(what, owner);
        if (reservedForOthers <= 0) {
            return requested;
        }

        long physicalAvailable = com.appliedenhancements.storage.InfiniteStorageSupport
                .physicalAvailable(storage, what, source);
        return Math.min(requested, subtractClamped(physicalAvailable, reservedForOthers));
    }

    private static InventoryReservationLedger<AEKey> ledger(MEStorage storage) {
        synchronized (LEDGERS) {
            return LEDGERS.get(storage);
        }
    }

    private static Map<AEKey, Long> positiveEntries(KeyCounter counter) {
        var result = new LinkedHashMap<AEKey, Long>();
        for (var entry : counter) {
            if (entry.getKey() != null && entry.getLongValue() > 0) {
                result.put(entry.getKey(), entry.getLongValue());
            }
        }
        return Map.copyOf(result);
    }

    private static long subtractClamped(long value, long deduction) {
        return value <= deduction ? 0 : value - deduction;
    }

    public static final class Reservation implements AutoCloseable {
        private final MEStorage storage;
        private final InventoryReservationLedger.Reservation<AEKey> delegate;

        private Reservation(
                MEStorage storage,
                InventoryReservationLedger.Reservation<AEKey> delegate) {
            this.storage = storage;
            this.delegate = delegate;
        }

        public long id() {
            return delegate.id();
        }

        public boolean active() {
            return delegate.active();
        }

        public <T> T submit(Supplier<T> action) {
            if (!delegate.active()) {
                return action.get();
            }
            Reservation previous = ACTIVE_SUBMISSION.get();
            ACTIVE_SUBMISSION.set(this);
            try {
                return action.get();
            } finally {
                if (previous == null) {
                    ACTIVE_SUBMISSION.remove();
                } else {
                    ACTIVE_SUBMISSION.set(previous);
                }
            }
        }

        private boolean activeFor(MEStorage candidate) {
            return delegate.active() && storage == candidate;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
