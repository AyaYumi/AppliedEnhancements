package com.github.appliedenhancements.crafting.aelis;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.github.appliedenhancements.integration.ae2.AelisScaledPattern;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Re-proves an old scaled job without changing its inventory, outstanding work or output. */
public final class AelisLegacyCycleRecovery {
    public record Recovery(Map<IPatternDetails, Long> tasks, AelisCycleExecutionPlan cyclePlan) {}

    private AelisLegacyCycleRecovery() {}

    public static Recovery prove(Map<IPatternDetails, Long> remaining, KeyCounter inventory,
            GenericStack output, AelisCycleSeedPolicy policy) {
        if (remaining.keySet().stream().noneMatch(AelisScaledPattern.class::isInstance)
                || output == null || output.amount() <= 0) return null;
        var normalized = new LinkedHashMap<IPatternDetails, Long>();
        var originals = new LinkedHashMap<IPatternDetails, IPatternDetails>();
        try {
            for (var entry : remaining.entrySet()) {
                IPatternDetails original = entry.getKey();
                long times = entry.getValue();
                if (times <= 0) continue;
                while (original instanceof AelisScaledPattern scaled) {
                    times = Math.multiplyExact(times, scaled.appliedenhancements$operationsPerPush());
                    original = scaled.appliedenhancements$originalPattern();
                }
                normalized.merge(original, times, Math::addExact);
                originals.put(entry.getKey(), original);
            }
            var variants = new LinkedHashMap<AEKey,
                    List<AelisCyclicDemandSolver.Variant<AEKey, IPatternDetails>>>();
            for (var pattern : normalized.keySet()) {
                var snapshot = AelisObservedPatternSemantics.captureStable(pattern);
                if (snapshot == null || snapshot.outputs().size() != 1) return null;
                var inputs = new ArrayList<AelisCyclicDemandSolver.Input<AEKey>>();
                for (var input : snapshot.inputs()) {
                    if (input.possibleInputs().size() != 1 || input.remainingKeys().get(0) != null) return null;
                    var stack = input.possibleInputs().get(0);
                    inputs.add(new AelisCyclicDemandSolver.Input<>(stack.what(), BigInteger.valueOf(
                            Math.multiplyExact(stack.amount(), input.multiplier()))));
                }
                var product = snapshot.outputs().get(0);
                variants.computeIfAbsent(product.what(), ignored -> new ArrayList<>()).add(
                        new AelisCyclicDemandSolver.Variant<>(pattern, product.what(),
                                BigInteger.valueOf(product.amount()), inputs));
            }
            var available = new LinkedHashMap<AEKey, BigInteger>();
            for (var entry : inventory) if (entry.getLongValue() > 0) {
                available.put(entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
            }
            var reserve = new LinkedHashMap<AEKey, BigInteger>();
            var limits = new AelisCyclicDemandSolver.Limits(256, 1_000_000,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            for (int pass = 0; pass < 8; pass++) {
                var demands = new LinkedHashMap<>(reserve);
                demands.merge(output.what(), BigInteger.valueOf(output.amount()), BigInteger::add);
                var result = AelisCyclicDemandSolver.solve(
                        new AelisCyclicDemandSolver.Problem<>(variants, demands, available), limits);
                if (!result.solved() || !result.plan().missing().isEmpty()
                        || result.plan().executionSchedule().isEmpty()) return null;
                var plan = result.plan();
                var steps = new ArrayList<AelisCycleExecutionPlan.Step>();
                var protectedKeys = new LinkedHashSet<AEKey>();
                for (var step : plan.executionSchedule()) {
                    Map<AEKey, Long> inputs = new LinkedHashMap<>();
                    step.inputsPerCraft().forEach((key, count) -> inputs.put(key, count.longValueExact()));
                    protectedKeys.addAll(inputs.keySet());
                    steps.add(new AelisCycleExecutionPlan.Step(step.id().getDefinition(),
                            step.firings().longValueExact(), inputs, step.selfReplenishingInputs()));
                }
                var seeds = new LinkedHashMap<AEKey, Long>();
                for (var entry : plan.requiredAvailable().entrySet()) {
                    if (protectedKeys.contains(entry.getKey())) seeds.put(entry.getKey(), entry.getValue().longValueExact());
                }
                var additions = policy == AelisCycleSeedPolicy.PRESERVE_MINIMUM
                        ? AelisCycleSeedReservation.additions(seeds, plan.surplus(), reserve) : Map.<AEKey, BigInteger>of();
                if (!additions.isEmpty()) {
                    additions.forEach((key, count) -> reserve.merge(key, count, BigInteger::add));
                    continue;
                }
                var counts = new LinkedHashMap<IPatternDetails, Long>();
                plan.firings().forEach((pattern, count) -> counts.put(pattern, count.longValueExact()));
                if (!counts.equals(normalized)) return null;
                var cycle = new AelisCycleExecutionPlan(steps, seeds, protectedKeys, policy);
                Set<AEKey> cyclicDefinitions = cycle.patternDefinitions();
                var tasks = new LinkedHashMap<IPatternDetails, Long>();
                for (var entry : remaining.entrySet()) {
                    var original = originals.get(entry.getKey());
                    if (entry.getValue() <= 0 || cyclicDefinitions.contains(original.getDefinition())) continue;
                    tasks.put(entry.getKey(), entry.getValue());
                }
                normalized.forEach((pattern, count) -> {
                    if (cyclicDefinitions.contains(pattern.getDefinition())) tasks.put(pattern, count);
                });
                return new Recovery(Map.copyOf(tasks), cycle);
            }
        } catch (RuntimeException invalid) {
            // An unprovable old job remains intact for manual inspection.
        }
        return null;
    }
}
