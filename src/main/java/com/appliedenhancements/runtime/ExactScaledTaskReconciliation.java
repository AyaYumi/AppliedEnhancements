package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
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
        var external = new java.util.HashSet<IPatternDetails>();
        var batches = new LinkedHashMap<IPatternDetails, Map<IPatternDetails, Long>>();
        for (var candidate : projected.keySet()) {
            if (exact.containsKey(candidate)) continue;
            ScaledTask scaled;
            try { scaled = resolver.apply(candidate); }
            catch (RuntimeException | LinkageError unavailable) { return originalTasks(exact); }
            if (scaled == null || scaled.original() == null || scaled.multiplier() <= 0) {
                return originalTasks(exact);
            }
            var original = findOriginal(exact, scaled.original());
            if (original == null) {
                return originalTasks(exact);
            }
            mapped.add(candidate);
            batches.computeIfAbsent(original, ignored -> new LinkedHashMap<>()).put(candidate, scaled.multiplier());
            if (SmartDoublingPatternAccess.isExternallyManaged(candidate)) external.add(original);
            if (scaled.multiplier() > scales.getOrDefault(original, 0L)) {
                chosen.put(original, candidate);
                scales.put(original, scaled.multiplier());
            }
        }
        if (chosen.isEmpty()) return originalTasks(exact);

        // An unrecognized rewrite falls back to the authoritative original tasks.
        for (var candidate : projected.keySet()) {
            if (!exact.containsKey(candidate) && !mapped.contains(candidate)) {
                return originalTasks(exact);
            }
        }
        var corrected = new LinkedHashMap<>(exact);
        chosen.forEach((original, batch) -> {
            var amount = corrected.remove(original);
            if (amount == null || amount.signum() <= 0) {
                return;
            }
            if (external.contains(original)) {
                // Keep the native provider split and wrapper identity when it conserves the exact work.
                var nativeTasks = new LinkedHashMap<>(batches.get(original));
                if (projected.containsKey(original)) nativeTasks.put(original, 1L);
                BigInteger nativeWork = BigInteger.ZERO;
                for (var task : nativeTasks.entrySet()) {
                    var pushes = projected.get(task.getKey());
                    if (pushes != null && pushes > 0) {
                        nativeWork = nativeWork.add(BigInteger.valueOf(pushes).multiply(BigInteger.valueOf(task.getValue())));
                    }
                }
                if (nativeWork.equals(amount)) {
                    nativeTasks.forEach((pattern, scale) -> corrected.put(pattern, BigInteger.valueOf(projected.get(pattern))));
                    return;
                }
                // Repair a mismatched or saturated projection from the exact original demand.
                // Retain the native wrapper and represent its final remainder separately.
            }
            var parts = amount.divideAndRemainder(BigInteger.valueOf(scales.get(original)));
            if (parts[0].signum() > 0) corrected.put(batch, parts[0]);
            // A smaller final batch uses the original pattern; no new encoded pattern or rounding.
            if (parts[1].signum() > 0) corrected.put(original, parts[1]);
        });
        var windows = new LinkedHashMap<IPatternDetails, Long>();
        corrected.forEach((pattern, amount) -> {
            if (amount == null || amount.signum() <= 0) {
                return;
            }
            windows.put(pattern, amount.min(WINDOW).longValueExact());
        });
        return new Result(Map.copyOf(windows), Map.copyOf(corrected));
    }

    private static Result originalTasks(Map<IPatternDetails, BigInteger> exact) {
        var tasks = new LinkedHashMap<IPatternDetails, BigInteger>();
        var windows = new LinkedHashMap<IPatternDetails, Long>();
        exact.forEach((pattern, amount) -> {
            if (pattern != null && amount != null && amount.signum() > 0) {
                tasks.put(pattern, amount);
                windows.put(pattern, amount.min(WINDOW).longValueExact());
            }
        });
        return new Result(Map.copyOf(windows), Map.copyOf(tasks));
    }

    private static IPatternDetails findOriginal(Map<IPatternDetails, BigInteger> exact,
            IPatternDetails original) {
        if (exact.containsKey(original)) return original;
        var definition = original.getDefinition();
        if (definition == null) return null;
        IPatternDetails found = null;
        for (var pattern : exact.keySet()) {
            if (definition.equals(pattern.getDefinition())) {
                if (found != null) return null;
                found = pattern;
            }
        }
        return found;
    }

    private static ScaledTask resolve(IPatternDetails pattern) {
        var scale = SmartDoublingPatternAccess.resolve(pattern);
        if (scale == null) return null;
        var original = scale.original();
        long multiplier = scale.multiplier();
        var visited = new java.util.IdentityHashMap<IPatternDetails, Boolean>();
        visited.put(pattern, Boolean.TRUE);
        while (original != null) {
            if (visited.put(original, Boolean.TRUE) != null) return null;
            var nested = SmartDoublingPatternAccess.resolve(original);
            if (nested == null) break;
            if (nested.multiplier() <= 0 || multiplier > Long.MAX_VALUE / nested.multiplier()) return null;
            multiplier *= nested.multiplier();
            original = nested.original();
        }
        return new ScaledTask(original, multiplier);
    }
}
