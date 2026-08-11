package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NetworkStorageDetectionCacheTest {
    @Test
    void prunesKeysThatDisappearFromTheNextRefresh() {
        var cache = new NetworkStorageDetectionCache<String>();

        cache.beginRefresh();
        cache.resolve("present", 10, () -> Optional.of(true));
        cache.resolve("removed", 20, () -> Optional.of(false));
        cache.endRefresh();
        assertEquals(2, cache.size());

        cache.beginRefresh();
        cache.resolve("present", 10, () -> Optional.of(true));
        cache.endRefresh();

        assertEquals(1, cache.size());
    }

    @Test
    void amountChangesTriggerAnImmediateRecheck() {
        var cache = new NetworkStorageDetectionCache<String>(100);
        var calls = new AtomicInteger();

        assertFalse(refresh(cache, "key", 10, () -> {
            calls.incrementAndGet();
            return Optional.of(false);
        }));
        assertTrue(refresh(cache, "key", 11, () -> {
            calls.incrementAndGet();
            return Optional.of(true);
        }));

        assertEquals(2, calls.get());
    }

    @Test
    void unchangedAmountsArePeriodicallyRechecked() {
        var cache = new NetworkStorageDetectionCache<String>(2);
        var infinite = new AtomicBoolean(true);
        var calls = new AtomicInteger();

        assertTrue(refresh(cache, "key", 10, () -> {
            calls.incrementAndGet();
            return Optional.of(infinite.get());
        }));
        infinite.set(false);
        assertTrue(refresh(cache, "key", 10, () -> {
            calls.incrementAndGet();
            return Optional.of(infinite.get());
        }));
        assertFalse(refresh(cache, "key", 10, () -> {
            calls.incrementAndGet();
            return Optional.of(infinite.get());
        }));

        assertEquals(2, calls.get());
    }

    @Test
    void failedProbesAreNotCachedAndRetryOnTheNextRefresh() {
        var cache = new NetworkStorageDetectionCache<String>();
        var calls = new AtomicInteger();

        assertFalse(refresh(cache, "key", 10, () -> {
            calls.incrementAndGet();
            return Optional.empty();
        }));
        assertEquals(0, cache.size());
        assertTrue(refresh(cache, "key", 10, () -> {
            calls.incrementAndGet();
            return Optional.of(true);
        }));

        assertEquals(2, calls.get());
        assertEquals(1, cache.size());
    }

    @Test
    void thrownProbeFailuresAlsoRetry() {
        var cache = new NetworkStorageDetectionCache<String>();

        assertFalse(refresh(cache, "key", 10, () -> {
            throw new IllegalStateException("transient failure");
        }));
        assertTrue(refresh(cache, "key", 10, () -> Optional.of(true)));
    }

    private static boolean refresh(NetworkStorageDetectionCache<String> cache,
            String key, long amount, java.util.function.Supplier<Optional<Boolean>> probe) {
        cache.beginRefresh();
        try {
            return cache.resolve(key, amount, probe);
        } finally {
            cache.endRefresh();
        }
    }
}
