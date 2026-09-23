package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import com.github.appliedenhancements.integration.ae2.AelisScaledPattern;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Reconciles a planning-time batch rewrite with the authoritative exact task ledger. */
public final class ExactScaledTaskReconciliation {
    private static final BigInteger WINDOW = BigInteger.valueOf(Long.MAX_VALUE - 1);

    private ExactScaledTaskReconciliation() {}

    public record Result(Map<IPatternDetails, Long> projected,
            Map<IPatternDetails, BigInteger> exact) {}

    record ScaledTask(IPatternDetails original, long multiplier) {}

    public static Result reconcile(Map<IPatternDetails, Long> projected,
            Map<IPatternDetails, BigInteger> exact) {
        return reconcile(projected, exact, ExactScaledTaskReconciliation::resolve);
    }

    static Result reconcile(Map<IPatternDetails, Long> projected,
            Map<IPatternDetails, BigInteger> exact,
            Function<IPatternDetails, ScaledTask> resolver) {
        // An already reconciled plan must retain both its batch and remainder tasks.
        if (exact.isEmpty() || exact.keySet().equals(projected.keySet())) {
            return new Result(projected, exact);
        }
        var chosen = new LinkedHashMap<IPatternDetails, IPatternDetails>();
        var scales = new LinkedHashMap<IPatternDetails, Long>();
        var mapped = new java.util.HashSet<IPatternDetails>();
        for (var candidate : projected.keySet()) {
            if (exact.containsKey(candidate)) continue;
            var scaled = resolver.apply(candidate);
            if (scaled == null) continue;
            if (scaled.original() == null || scaled.multiplier() <= 0) {
                throw new IllegalStateException("Invalid scaled crafting task");
            }
            var original = findOriginal(exact, scaled.original());
            if (original == null) {
                throw new IllegalStateException("Scaled crafting task has no exact original");
            }
            mapped.add(candidate);
            if (scaled.multiplier() > scales.getOrDefault(original, 0L)) {
                chosen.put(original, candidate);
                scales.put(original, scaled.multiplier());
            }
        }
        if (chosen.isEmpty()) return new Result(projected, exact);

        // Never silently discard an unrelated task introduced by another integration.
        for (var candidate : projected.keySet()) {
            if (!exact.containsKey(candidate) && !mapped.contains(candidate)) {
                throw new IllegalStateException("Cannot reconcile unknown rewritten crafting task");
            }
        }
        var corrected = new LinkedHashMap<>(exact);
        chosen.forEach((original, batch) -> {
            var amount = corrected.remove(original);
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalStateException("Invalid exact original crafting count");
            }
            var parts = amount.divideAndRemainder(BigInteger.valueOf(scales.get(original)));
            if (parts[0].signum() > 0) corrected.put(batch, parts[0]);
            // A smaller final batch uses the original pattern; no new encoded pattern or rounding.
            if (parts[1].signum() > 0) corrected.put(original, parts[1]);
        });
        var windows = new LinkedHashMap<IPatternDetails, Long>();
        corrected.forEach((pattern, amount) -> {
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalStateException("Invalid exact crafting count");
            }
            windows.put(pattern, amount.min(WINDOW).longValueExact());
        });
        return new Result(Map.copyOf(windows), Map.copyOf(corrected));
    }

    private static IPatternDetails findOriginal(Map<IPatternDetails, BigInteger> exact,
            IPatternDetails original) {
        if (exact.containsKey(original)) return original;
        var definition = original.getDefinition();
        if (definition == null) return null;
        IPatternDetails found = null;
        for (var pattern : exact.keySet()) {
            if (definition.equals(pattern.getDefinition())) {
                if (found != null) throw new IllegalStateException("Ambiguous exact original crafting task");
                found = pattern;
            }
        }
        return found;
    }

    private static ScaledTask resolve(IPatternDetails pattern) {
        if (!(pattern instanceof AelisScaledPattern scaled)) return null;
        return new ScaledTask(scaled.appliedenhancements$originalPattern(),
                scaled.appliedenhancements$operationsPerPush());
    }
}
