package com.appliedenhancements.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Double-buffered inverted index from a storage key to candidate external slots.
 *
 * <p>The active index remains available while a new inventory listing is being
 * assembled. A rebuild only becomes visible after {@link #commitRebuild()}, so
 * an interrupted external inventory scan cannot publish a partial index.</p>
 *
 * <p>Entries in this index are hints. Callers must validate every slot and fall
 * back to the underlying inventory when the candidates are insufficient.</p>
 */
public final class StorageBusSlotIndex<K> {
    private static final CandidateSlots EMPTY = new CandidateSlots();

    private Map<K, CandidateSlots> active = new HashMap<>();
    private Map<K, CandidateSlots> rebuilding = new HashMap<>();
    private boolean rebuildOpen;

    /** Starts a new rebuild without changing the currently published index. */
    public void beginRebuild() {
        for (var slots : rebuilding.values()) {
            slots.reset();
        }
        rebuildOpen = true;
    }

    /** Records one candidate slot in the rebuild currently in progress. */
    public void record(K key, int slot) {
        if (!rebuildOpen || key == null || slot < 0) {
            return;
        }

        rebuilding.computeIfAbsent(key, ignored -> new CandidateSlots()).add(slot);
    }

    /** Publishes the completed rebuild atomically. */
    public void commitRebuild() {
        if (!rebuildOpen) {
            return;
        }

        rebuilding.values().removeIf(CandidateSlots::isEmpty);
        var previous = active;
        active = rebuilding;
        rebuilding = previous;
        rebuildOpen = false;
    }

    /** Discards a partial rebuild and keeps the previous published index. */
    public void abortRebuild() {
        rebuildOpen = false;
    }

    /**
     * Returns the published candidates for {@code key}. The returned view is
     * owned by this index and must not be retained across later rebuilds.
     */
    public CandidateSlots candidates(K key) {
        Objects.requireNonNull(key, "key");
        return active.getOrDefault(key, EMPTY);
    }

    /** Compact primitive slot list used as a read-only published view. */
    public static final class CandidateSlots {
        private int[] values = new int[1];
        private int size;

        private void add(int slot) {
            if (size == values.length) {
                int[] grown = new int[values.length << 1];
                System.arraycopy(values, 0, grown, 0, values.length);
                values = grown;
            }
            values[size++] = slot;
        }

        private void reset() {
            size = 0;
        }

        public int size() {
            return size;
        }

        public boolean isEmpty() {
            return size == 0;
        }

        public int get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            return values[index];
        }
    }
}
