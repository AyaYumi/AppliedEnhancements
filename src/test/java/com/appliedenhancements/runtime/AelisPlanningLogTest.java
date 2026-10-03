package com.appliedenhancements.runtime;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AelisPlanningLogTest {
    @Test void repeatedCategoryIsLimitedButIndependentCategoriesRemainVisible() {
        var time = new AtomicLong(100);
        var limiter = new AelisPlanningLog.RateLimiter(10, 8, time::get);
        assertTrue(limiter.permit("shortage"));
        assertFalse(limiter.permit("shortage"));
        assertTrue(limiter.permit("internal"));
        time.addAndGet(9);
        assertFalse(limiter.permit("shortage"));
        time.incrementAndGet();
        assertTrue(limiter.permit("shortage"));
    }

    @Test void categoryStorageIsBounded() {
        var time = new AtomicLong(1);
        var limiter = new AelisPlanningLog.RateLimiter(10, 2, time::get);
        assertTrue(limiter.permit("a"));
        assertTrue(limiter.permit("b"));
        assertTrue(limiter.permit("c"));
        assertFalse(limiter.permit("a"), "overflow must not reset known category history");
        assertFalse(limiter.permit("d"), "excess categories share one overflow window");
        for (int attempt = 0; attempt < 10_000; attempt++) {
            assertFalse(limiter.permit("a"));
            assertFalse(limiter.permit("excess-" + attempt));
        }
        time.addAndGet(10);
        assertTrue(limiter.permit("a"));
        assertTrue(limiter.permit("b"));
        assertTrue(limiter.permit("c"));
        assertFalse(limiter.permit("d"));
    }
    @Test void concurrentMachinesShareOneEmission() {
        var limiter = new AelisPlanningLog.RateLimiter(10, 8, () -> 0);
        var emitted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.stream.IntStream.range(0, 1000).parallel().forEach(i -> {
            if (limiter.permit("provider-failure")) emitted.incrementAndGet();
        });
        assertEquals(1, emitted.get());
    }

    @Test void monotonicClockWrapKeepsTheWindow() {
        var time = new AtomicLong(Long.MAX_VALUE - 5);
        var limiter = new AelisPlanningLog.RateLimiter(10, 8, time::get);
        assertTrue(limiter.permit("failure"));
        time.set(Long.MIN_VALUE + 3);
        assertFalse(limiter.permit("failure"));
        time.incrementAndGet();
        assertTrue(limiter.permit("failure"));
    }
}
