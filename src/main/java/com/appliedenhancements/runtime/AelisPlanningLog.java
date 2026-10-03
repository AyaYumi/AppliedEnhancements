package com.appliedenhancements.runtime;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import java.time.Duration;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Keeps repeated crafting and compatibility failures from flooding logs. */
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

    /** Detailed process messages remain opt-in and share the global template window. */
    public static void trace(String message, Object... arguments) {
        diagnostic(message, message, arguments);
    }

    public static void warn(String message, Object... arguments) {
        warning(message, message, arguments);
    }

    public static void error(String message, Object... arguments) {
        if (AppliedEnhancements.LOGGER.isErrorEnabled() && LIMITER.permit("error:" + message)) {
            AppliedEnhancements.LOGGER.error(message, arguments);
        }
    }

    public static void debug(String message, Object... arguments) {
        if (AppliedEnhancements.LOGGER.isDebugEnabled() && LIMITER.permit("debug:" + message)) {
            AppliedEnhancements.LOGGER.debug(message, arguments);
        }
    }

    static final class RateLimiter {
        private final long intervalNanos;
        private final int maximumCategories;
        private final LongSupplier clock;
        private final Map<String, Long> lastEmission = new HashMap<>();
        private boolean overflowRecorded;
        private long overflowEmission;

        RateLimiter(long intervalNanos, int maximumCategories, LongSupplier clock) {
            if (intervalNanos <= 0 || maximumCategories <= 0) {
                throw new IllegalArgumentException("Rate limit bounds must be positive");
            }
            this.intervalNanos = intervalNanos;
            this.maximumCategories = maximumCategories;
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        synchronized boolean permit(String category) {
            Objects.requireNonNull(category, "category");
            long now = clock.getAsLong();
            var previous = lastEmission.get(category);
            if (previous != null && now - previous < intervalNanos) return false;
            if (previous == null && lastEmission.size() >= maximumCategories) {
                // Preserve known categories. Excess categories share one bounded
                // window instead of clearing history and letting retries flood logs.
                if (overflowRecorded && now - overflowEmission < intervalNanos) return false;
                overflowRecorded = true;
                overflowEmission = now;
                return true;
            }
            lastEmission.put(category, now);
            return true;
        }
    }
}
