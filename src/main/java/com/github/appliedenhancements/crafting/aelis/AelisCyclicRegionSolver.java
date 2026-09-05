package com.github.appliedenhancements.crafting.aelis;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Solves one supported cyclic region while leaving external inputs delegated. */
final class AelisCyclicRegionSolver {
    record Plan<K, V>(Map<V, BigInteger> firings,
            Map<K, BigInteger> missingSeeds,
            Map<K, BigInteger> requiredAvailable,
            Map<K, BigInteger> externalDemands,
            Map<K, BigInteger> surplus,
            List<AelisCyclicDemandSolver.ScheduleStep<K, V>> executionSchedule,
            long exploredStates) {
        Plan {
            firings = Map.copyOf(firings);
            missingSeeds = Map.copyOf(missingSeeds);
            requiredAvailable = Map.copyOf(requiredAvailable);
            externalDemands = Map.copyOf(externalDemands);
            surplus = Map.copyOf(surplus);
            executionSchedule = List.copyOf(executionSchedule);
        }
    }

    record Result<K, V>(Plan<K, V> plan,
            AelisCyclicDemandSolver.Failure failure,
            long exploredStates) {
        boolean solved() {
            return plan != null && failure == AelisCyclicDemandSolver.Failure.NONE;
        }
    }

    private AelisCyclicRegionSolver() {
    }

    static <K, V> Result<K, V> solve(
            AelisCyclicRegionDetector.Region<K, V> region,
            K demandKey, BigInteger demand,
            Map<K, BigInteger> available,
            AelisCyclicDemandSolver.Limits limits) {
        return solve(region, demandKey, demand, Map.of(), available, limits);
    }

    static <K, V> Result<K, V> solve(
            AelisCyclicRegionDetector.Region<K, V> region,
            K demandKey, BigInteger demand,
            Map<K, BigInteger> additionalDemands,
            Map<K, BigInteger> available,
            AelisCyclicDemandSolver.Limits limits) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(demandKey, "demandKey");
        Objects.requireNonNull(demand, "demand");
        Objects.requireNonNull(additionalDemands, "additionalDemands");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(limits, "limits");
        if (!region.keys().contains(demandKey) || demand.signum() <= 0) {
            throw new IllegalArgumentException("Demand must target the cyclic region");
        }

        var regionAvailable = new LinkedHashMap<K, BigInteger>();
        for (K key : region.keys()) {
            BigInteger amount = available.get(key);
            if (amount != null && amount.signum() > 0) {
                regionAvailable.put(key, amount);
            }
        }
        var demands = new LinkedHashMap<K, BigInteger>();
        demands.put(demandKey, demand);
        for (var entry : additionalDemands.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && entry.getValue().signum() > 0) {
                demands.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }
        var raw = AelisCyclicDemandSolver.solve(
                new AelisCyclicDemandSolver.Problem<>(
                        region.variants(), demands, regionAvailable),
                limits);
        if (!raw.solved()) {
            return new Result<>(null, raw.failure(), raw.exploredStates());
        }

        AelisCyclicDemandSolver.Plan<K, V> rawPlan = raw.plan();
        var missingSeeds = includeRegionKeys(rawPlan.missing(), region);
        var requiredAvailable = includeRegionKeys(
                rawPlan.requiredAvailable(), region);
        var surplus = includeRegionKeys(rawPlan.surplus(), region);
        var externalDemands = new LinkedHashMap<K, BigInteger>();
        for (var entry : rawPlan.demands().entrySet()) {
            if (!region.keys().contains(entry.getKey())
                    && entry.getValue().signum() > 0) {
                externalDemands.put(entry.getKey(), entry.getValue());
            }
        }
        return new Result<>(
                new Plan<>(rawPlan.firings(), missingSeeds,
                        requiredAvailable, externalDemands,
                        surplus, rawPlan.executionSchedule(),
                        rawPlan.exploredStates()),
                AelisCyclicDemandSolver.Failure.NONE,
                raw.exploredStates());
    }

    private static <K, V> Map<K, BigInteger> includeRegionKeys(
            Map<K, BigInteger> values,
            AelisCyclicRegionDetector.Region<K, V> region) {
        var result = new LinkedHashMap<K, BigInteger>();
        for (var entry : values.entrySet()) {
            if (region.keys().contains(entry.getKey())
                    && entry.getValue().signum() > 0) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
