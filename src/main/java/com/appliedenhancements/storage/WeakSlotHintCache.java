package com.appliedenhancements.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(key, "key");
        var hints = hintsByHandler.get(handler);
        return hints == null ? -1 : hints.getOrDefault(key, -1);
    }

    public synchronized void remember(H handler, K key, int slot) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(key, "key");
        if (slot < 0) {
            forget(handler, key);
            return;
        }

        hintsByHandler.computeIfAbsent(
                handler, ignored -> new BoundedSlotMap<>(maxKeysPerHandler))
                .put(key, slot);
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

    private static final class BoundedSlotMap<K> extends LinkedHashMap<K, Integer> {
        private final int maximumSize;

        private BoundedSlotMap(int maximumSize) {
            super(16, 0.75f, true);
            this.maximumSize = maximumSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, Integer> eldest) {
            return size() > maximumSize;
        }
    }
}
