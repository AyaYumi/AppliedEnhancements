package com.appliedenhancements.runtime;

import com.appliedenhancements.AppliedEnhancements;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Keeps repeated crafting and compatibility failures from flooding logs. */
public final class AelisPlanningLog {
    private static final RateLimiter WARNINGS =
            new RateLimiter(Duration.ofMinutes(1).toNanos(), 128, 8, System::nanoTime);
    private static final RateLimiter ERRORS =
            new RateLimiter(Duration.ofMinutes(1).toNanos(), 128, 4, System::nanoTime);

    private AelisPlanningLog() {}

    /** Actual compatibility defects remain warnings, but automatic retry loops cannot spam them. */
    public static void warning(String category, String message, Object... arguments) {
        if (AppliedEnhancements.LOGGER.isWarnEnabled() && WARNINGS.permit(category)) {
            AppliedEnhancements.LOGGER.warn(message, arguments);
        }
    }

    public static void warn(String message, Object... arguments) {
        warning(message, message, arguments);
    }

    public static void error(String message, Object... arguments) {
        if (AppliedEnhancements.LOGGER.isErrorEnabled() && ERRORS.permit(message)) {
            AppliedEnhancements.LOGGER.error(message, arguments);
        }
    }

    static final class RateLimiter {
        private final long intervalNanos;
        private final int maximumCategories;
        private final int maximumEmissions;
        private final LongSupplier clock;
        private final Map<String, Long> lastEmission = new HashMap<>();
        private final ArrayDeque<Long> emissions = new ArrayDeque<>();
        private boolean overflowRecorded;
        private long overflowEmission;

        RateLimiter(long intervalNanos, int maximumCategories, int maximumEmissions,
                LongSupplier clock) {
            if (intervalNanos <= 0 || maximumCategories <= 0 || maximumEmissions <= 0) {
                throw new IllegalArgumentException("Rate limit bounds must be positive");
            }
            this.intervalNanos = intervalNanos;
            this.maximumCategories = maximumCategories;
            this.maximumEmissions = maximumEmissions;
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        synchronized boolean permit(String category) {
            Objects.requireNonNull(category, "category");
            long now = clock.getAsLong();
            var previous = lastEmission.get(category);
            if (previous != null && now - previous < intervalNanos) return false;
            boolean overflow = previous == null && lastEmission.size() >= maximumCategories;
            if (overflow && overflowRecorded && now - overflowEmission < intervalNanos) return false;
            while (!emissions.isEmpty() && now - emissions.peekFirst() >= intervalNanos) {
                emissions.removeFirst();
            }
            if (emissions.size() >= maximumEmissions) return false;
            if (overflow) {
                // Keep known category history when unregistered integrations exceed the bound.
                overflowRecorded = true;
                overflowEmission = now;
            } else {
                lastEmission.put(category, now);
            }
            emissions.addLast(now);
            return true;
        }
    }
}
