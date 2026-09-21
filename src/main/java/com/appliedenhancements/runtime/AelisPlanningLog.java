package com.appliedenhancements.runtime;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Keeps repeated automatic crafting attempts from flooding server logs. */
public final class AelisPlanningLog {
    private static final RateLimiter LIMITER =
            new RateLimiter(Duration.ofMinutes(1).toNanos(), 128, System::nanoTime);

    private AelisPlanningLog() {}

    /** Expected planning outcomes are visible only in diagnostics, at most once per category/window. */
    public static void diagnostic(String category, String message, Object... arguments) {
        if (Config.AELIS_DIAGNOSTICS.get() && LIMITER.permit("diagnostic:" + category)) {
            AppliedEnhancements.LOGGER.info(message, arguments);
        }
    }

    /** Actual compatibility defects remain warnings, but automatic retry loops cannot spam them. */
    public static void warning(String category, String message, Object... arguments) {
        if (LIMITER.permit("warning:" + category)) {
            AppliedEnhancements.LOGGER.warn(message, arguments);
        }
    }

    static final class RateLimiter {
        private final long intervalNanos;
        private final int maximumCategories;
        private final LongSupplier clock;
        private final ConcurrentHashMap<String, Long> lastEmission = new ConcurrentHashMap<>();

        RateLimiter(long intervalNanos, int maximumCategories, LongSupplier clock) {
            if (intervalNanos <= 0 || maximumCategories <= 0) {
                throw new IllegalArgumentException("Rate limit bounds must be positive");
            }
            this.intervalNanos = intervalNanos;
            this.maximumCategories = maximumCategories;
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        boolean permit(String category) {
            Objects.requireNonNull(category, "category");
            if (!lastEmission.containsKey(category)
                    && lastEmission.size() >= maximumCategories) {
                lastEmission.clear();
            }
            long now = clock.getAsLong();
            var permitted = new AtomicBoolean();
            lastEmission.compute(category, (ignored, previous) -> {
                if (previous == null || now - previous >= intervalNanos) {
                    permitted.set(true);
                    return now;
                }
                return previous;
            });
            return permitted.get();
        }
    }
}
