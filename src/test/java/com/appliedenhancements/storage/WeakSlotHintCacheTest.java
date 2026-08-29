package com.appliedenhancements.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WeakSlotHintCacheTest {
    @Test
    void isolatesHandlersAndKeys() {
        var cache = new WeakSlotHintCache<Object, String>(4);
        var first = new Object();
        var second = new Object();

        cache.remember(first, "iron", 7);
        cache.remember(second, "iron", 3);

        assertEquals(7, cache.find(first, "iron"));
        assertEquals(3, cache.find(second, "iron"));
        assertEquals(-1, cache.find(first, "gold"));
    }

    @Test
    void evictsLeastRecentlyUsedKeyPerHandler() {
        var cache = new WeakSlotHintCache<Object, String>(2);
        var handler = new Object();

        cache.remember(handler, "iron", 1);
        cache.remember(handler, "gold", 2);
        assertEquals(1, cache.find(handler, "iron"));
        cache.remember(handler, "copper", 3);

        assertEquals(1, cache.find(handler, "iron"));
        assertEquals(-1, cache.find(handler, "gold"));
        assertEquals(3, cache.find(handler, "copper"));
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeakSlotHintCache<>(0));
    }
}
