package com.appliedenhancements.pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-indexes initially empty target slots for one batch pattern-move plan.
 *
 * <p>Reservations are tracked by candidate index in a bit set. A slot that
 * rejects one query remains available to later queries because acceptance may
 * depend on the pattern type.</p>
 */
public final class PatternMoveSlotIndex<T> {
    private final List<TargetSlots<T>> targets;

    public PatternMoveSlotIndex(
            Map<Long, T> targets,
            SlotCount<T> slotCount,
            SlotTest<T> isInitiallyEmpty) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(slotCount, "slotCount");
        Objects.requireNonNull(isInitiallyEmpty, "isInitiallyEmpty");

        this.targets = new ArrayList<>(targets.size());
        for (var entry : targets.entrySet()) {
            T target = Objects.requireNonNull(entry.getValue(), "target");
            int size = slotCount.get(target);
            if (size < 0) {
                throw new IllegalArgumentException("slot count must not be negative");
            }

            int[] emptySlots = new int[size];
            int emptyCount = 0;
            for (int slot = 0; slot < size; slot++) {
                if (isInitiallyEmpty.test(target, slot)) {
                    emptySlots[emptyCount++] = slot;
                }
            }
            this.targets.add(new TargetSlots<>(
                    entry.getKey(), target, Arrays.copyOf(emptySlots, emptyCount)));
        }
    }

    /**
     * Finds and reserves the first accepting target slot. A preferred slot is
     * considered first only when exactly one target exists, matching AE2's
     * existing batch-move behavior.
     */
    public <Q> Candidate<T> findAndReserve(
            Q query,
            int preferredSlot,
            SlotAcceptance<T, Q> accepts) {
        Objects.requireNonNull(accepts, "accepts");

        TargetSlots<T> preferredTarget = null;
        int preferredCandidate = -1;
        if (preferredSlot >= 0 && targets.size() == 1) {
            preferredTarget = targets.getFirst();
            preferredCandidate = Arrays.binarySearch(preferredTarget.emptySlots, preferredSlot);
            if (preferredCandidate >= 0
                    && !preferredTarget.reserved.get(preferredCandidate)
                    && accepts.test(preferredTarget.target, preferredSlot, query)) {
                preferredTarget.reserved.set(preferredCandidate);
                return new Candidate<>(preferredTarget.id, preferredTarget.target, preferredSlot);
            }
        }

        for (var target : targets) {
            for (int candidate = target.reserved.nextClearBit(0);
                    candidate < target.emptySlots.length;
                    candidate = target.reserved.nextClearBit(candidate + 1)) {
                if (target == preferredTarget && candidate == preferredCandidate) {
                    continue; // The same unchanged slot already rejected this query.
                }

                int slot = target.emptySlots[candidate];
                if (accepts.test(target.target, slot, query)) {
                    target.reserved.set(candidate);
                    return new Candidate<>(target.id, target.target, slot);
                }
            }
        }

        return null;
    }

    public record Candidate<T>(long targetId, T target, int slot) {
    }

    @FunctionalInterface
    public interface SlotCount<T> {
        int get(T target);
    }

    @FunctionalInterface
    public interface SlotTest<T> {
        boolean test(T target, int slot);
    }

    @FunctionalInterface
    public interface SlotAcceptance<T, Q> {
        boolean test(T target, int slot, Q query);
    }

    private static final class TargetSlots<T> {
        private final long id;
        private final T target;
        private final int[] emptySlots;
        private final BitSet reserved;

        private TargetSlots(long id, T target, int[] emptySlots) {
            this.id = id;
            this.target = target;
            this.emptySlots = emptySlots;
            this.reserved = new BitSet(emptySlots.length);
        }
    }
}
