package com.appliedenhancements.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe, all-or-nothing reservation accounting independent of AE2. */
final class InventoryReservationLedger<K> {
    private final AtomicLong nextId = new AtomicLong();
    private final Map<K, Long> reserved = new LinkedHashMap<>();

    synchronized Reservation<K> tryAcquire(
            Map<K, Long> available, Map<K, Long> requested) {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(requested, "requested");
        var owned = new LinkedHashMap<K, Long>();
        for (var entry : requested.entrySet()) {
            K key = Objects.requireNonNull(entry.getKey(), "requested key");
            long amount = Objects.requireNonNull(entry.getValue(), "requested amount");
            if (amount <= 0) {
                continue;
            }
            long physical = Math.max(0, available.getOrDefault(key, 0L));
            long alreadyReserved = reserved.getOrDefault(key, 0L);
            if (amount > subtractClamped(physical, alreadyReserved)) {
                return null;
            }
            owned.put(key, amount);
        }

        for (var entry : owned.entrySet()) {
            reserved.merge(entry.getKey(), entry.getValue(), Math::addExact);
        }
        return new Reservation<>(nextId.incrementAndGet(), this, Map.copyOf(owned));
    }

    synchronized long reservedForOthers(K key, Reservation<K> owner) {
        long total = reserved.getOrDefault(key, 0L);
        long owned = owner != null && owner.active && owner.ledger == this
                ? owner.amounts.getOrDefault(key, 0L)
                : 0;
        return subtractClamped(total, owned);
    }

    synchronized Map<K, Long> snapshot() {
        return Map.copyOf(reserved);
    }

    synchronized boolean isEmpty() {
        return reserved.isEmpty();
    }

    private synchronized void release(Reservation<K> reservation) {
        if (!reservation.active || reservation.ledger != this) {
            return;
        }
        reservation.active = false;
        for (var entry : reservation.amounts.entrySet()) {
            K key = entry.getKey();
            long remaining = subtractClamped(
                    reserved.getOrDefault(key, 0L), entry.getValue());
            if (remaining == 0) {
                reserved.remove(key);
            } else {
                reserved.put(key, remaining);
            }
        }
    }

    private static long subtractClamped(long value, long deduction) {
        return value <= deduction ? 0 : value - deduction;
    }

    static final class Reservation<K> implements AutoCloseable {
        private final long id;
        private final InventoryReservationLedger<K> ledger;
        private final Map<K, Long> amounts;
        private boolean active = true;

        private Reservation(
                long id,
                InventoryReservationLedger<K> ledger,
                Map<K, Long> amounts) {
            this.id = id;
            this.ledger = ledger;
            this.amounts = amounts;
        }

        long id() {
            return id;
        }

        Map<K, Long> amounts() {
            return amounts;
        }

        boolean active() {
            return active;
        }

        @Override
        public void close() {
            ledger.release(this);
        }
    }
}
