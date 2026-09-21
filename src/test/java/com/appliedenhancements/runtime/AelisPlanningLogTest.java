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
        assertTrue(limiter.permit("a"), "bounded reset must not permanently suppress old categories");
    }
}
