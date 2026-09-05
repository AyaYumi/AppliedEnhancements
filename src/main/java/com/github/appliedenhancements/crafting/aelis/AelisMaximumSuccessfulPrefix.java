package com.github.appliedenhancements.crafting.aelis;

import java.util.Objects;

/** Finds the largest successful prefix of a monotonic transactional attempt. */
final class AelisMaximumSuccessfulPrefix {
    enum Outcome {
        APPLIED,
        SHORTAGE,
        FALLBACK
    }

    record ProbeResult<T>(Outcome outcome, T value) {
        ProbeResult {
            Objects.requireNonNull(outcome, "outcome");
        }

        static <T> ProbeResult<T> applied(T value) {
            return new ProbeResult<>(Outcome.APPLIED, value);
        }

        static <T> ProbeResult<T> shortage() {
            return new ProbeResult<>(Outcome.SHORTAGE, null);
        }

        static <T> ProbeResult<T> fallback() {
            return new ProbeResult<>(Outcome.FALLBACK, null);
        }
    }

    @FunctionalInterface
    interface Probe<T> {
        ProbeResult<T> tryAmount(long amount) throws InterruptedException;
    }

    record Result<T>(boolean fallback, long allocation, int probes, T value) {
    }

    private AelisMaximumSuccessfulPrefix() {
    }

    /**
     * The caller has already established that {@code requested} itself is a
     * shortage. Every probe must own a disposable transaction.
     */
    static <T> Result<T> find(long requested, Probe<T> probe)
            throws InterruptedException {
        Objects.requireNonNull(probe, "probe");
        if (requested <= 1) {
            return new Result<>(false, 0, 0, null);
        }

        long low = 0;
        long high = requested;
        int probes = 0;
        T best = null;
        while (high - low > 1) {
            long trial = AelisExecutionPolicy.upperMidpoint(low, high);
            probes++;
            ProbeResult<T> result = probe.tryAmount(trial);
            if (result == null || result.outcome() == Outcome.FALLBACK) {
                return new Result<>(true, 0, probes, null);
            }
            if (result.outcome() == Outcome.APPLIED) {
                low = trial;
                best = result.value();
            } else {
                high = trial;
            }
        }
        return new Result<>(false, low, probes, best);
    }
}
