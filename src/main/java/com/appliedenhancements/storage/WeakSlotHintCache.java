package com.appliedenhancements.storage;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Weakly keys bounded per-handler maps of recently successful slots.
 *
 * <p>The weak outer keys avoid retaining unloaded external inventories. The
 * inner access-ordered map keeps repeated transfers fast without allowing a
 * handler that sees many distinct keys to grow the cache without bound.</p>
 */
public final class WeakSlotHintCache<H, K> {
    private final Map<H, BoundedSlotMap<K>> hintsByHandler = new WeakHashMap<>();
    private final int maxKeysPerHandler;

    public WeakSlotHintCache(int maxKeysPerHandler) {
        if (maxKeysPerHandler <= 0) {
            throw new IllegalArgumentException("maxKeysPerHandler must be positive");
        }
        this.maxKeysPerHandler = maxKeysPerHandler;
    }

    public synchronized int find(H handler, K key) {
        var slots = findAll(handler, key);
        return slots.isEmpty() ? -1 : slots.get(0);
    }

    public synchronized List<Integer> findAll(H handler, K key) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(key, "key");
        var hints = hintsByHandler.get(handler);
        return hints == null ? List.of() : hints.getOrDefault(key, List.of());
    }

    public synchronized void remember(H handler, K key, int slot) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(key, "key");
        if (slot < 0) {
            forget(handler, key);
            return;
        }

        var updated = new TreeSet<>(findAll(handler, key));
        updated.add(slot);
        rememberAll(handler, key, updated);
    }

    public synchronized void rememberAll(H handler, K key, Iterable<Integer> slots) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(key, "key");
        var ordered = new TreeSet<Integer>();
        for (Integer slot : slots) {
            if (slot != null && slot >= 0) {
                ordered.add(slot);
            }
        }
        if (ordered.isEmpty()) {
            forget(handler, key);
            return;
        }

        var snapshot = new ArrayList<Integer>(ordered);
        hintsByHandler.computeIfAbsent(
                handler, ignored -> new BoundedSlotMap<>(maxKeysPerHandler))
                .put(key, List.copyOf(snapshot));
    }

    public synchronized void forget(H handler, K key) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(key, "key");
        var hints = hintsByHandler.get(handler);
        if (hints != null) {
            hints.remove(key);
            if (hints.isEmpty()) {
                hintsByHandler.remove(handler);
            }
        }
    }

    private static final class BoundedSlotMap<K> extends LinkedHashMap<K, List<Integer>> {
        private final int maximumSize;

        private BoundedSlotMap(int maximumSize) {
            super(16, 0.75f, true);
            this.maximumSize = maximumSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, List<Integer>> eldest) {
            return size() > maximumSize;
        }
    }
}
