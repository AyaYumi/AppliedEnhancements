package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Finds the ordinary prerequisites that must remain runnable during cyclic production. */
public final class AelisCyclePhaseAnalysis {
    private AelisCyclePhaseAnalysis() {}

    public static AelisCycleExecutionPlan prepare(
            AelisCycleExecutionPlan cycle, Map<IPatternDetails, Long> tasks) {
        return prepare(cycle, tasks, ignored -> null);
    }

    /**
     * Tasks must already have their cyclic quantity wrappers normalized. The resolver can
     * recover a completed pattern that no longer appears in a saved CPU's pending tasks.
     */
    public static AelisCycleExecutionPlan prepare(
            AelisCycleExecutionPlan cycle, Map<IPatternDetails, Long> tasks,
            Function<AEKey, IPatternDetails> resolver) {
        if (cycle == null) return null;
        try {
            var pending = new ArrayList<Pattern>();
            var known = new LinkedHashMap<AEKey, Pattern>();
            for (var entry : tasks.entrySet()) {
                var pattern = entry.getKey();
                var description = describe(pattern.getDefinition(), pattern);
                known.put(description.definition(), description);
                if (entry.getValue() > 0) pending.add(description);
            }
            var cyclic = new LinkedHashMap<AEKey, Pattern>();
            for (AEKey definition : cycle.patternDefinitions()) {
                Pattern description = known.get(definition);
                if (description == null) {
                    IPatternDetails restored = resolver.apply(definition);
                    if (restored != null && definition.equals(restored.getDefinition())) {
                        description = describe(definition, restored);
                    }
                }
                if (description != null) cyclic.put(definition, description);
            }
            return prepareGraph(cycle, pending, cyclic);
        } catch (RuntimeException unavailable) {
            // Do not impose a new execution barrier when an optional pattern cannot be inspected.
            return cycle;
        }
    }

    static AelisCycleExecutionPlan prepareGraph(
            AelisCycleExecutionPlan cycle, List<Pattern> pending,
            Map<AEKey, Pattern> cyclic) {
        Set<AEKey> cycleDefinitions = cycle.patternDefinitions();
        boolean incomplete = !cyclic.keySet().containsAll(cycleDefinitions);
        var previous = cycle.phase();
        if (incomplete && (previous == null
                || !previous.outputsPerPattern().keySet().containsAll(cycleDefinitions))) {
            return cycle;
        }

        var outputs = new LinkedHashMap<AEKey, Map<AEKey, Long>>();
        if (incomplete) outputs.putAll(previous.outputsPerPattern());
        cyclic.forEach((definition, pattern) -> outputs.put(definition, pattern.outputs()));

        var producers = new LinkedHashMap<AEKey, List<Pattern>>();
        var byDefinition = new LinkedHashMap<AEKey, Pattern>();
        for (Pattern pattern : pending) {
            byDefinition.put(pattern.definition(), pattern);
            if (cycleDefinitions.contains(pattern.definition())) continue;
            for (AEKey output : pattern.producedKeys()) {
                producers.computeIfAbsent(output, ignored -> new ArrayList<>()).add(pattern);
            }
        }

        var prerequisites = new LinkedHashSet<AEKey>();
        var requiredInputs = new ArrayDeque<AEKey>();
        cyclic.values().forEach(pattern -> requiredInputs.addAll(pattern.inputs()));
        if (incomplete) {
            prerequisites.addAll(previous.prerequisitePatterns());
            for (AEKey definition : prerequisites) {
                Pattern pattern = byDefinition.get(definition);
                if (pattern != null) requiredInputs.addAll(pattern.inputs());
            }
        }
        var visitedInputs = new LinkedHashSet<AEKey>();
        while (!requiredInputs.isEmpty()) {
            AEKey input = requiredInputs.removeFirst();
            if (!visitedInputs.add(input)) continue;
            for (Pattern producer : producers.getOrDefault(input, List.of())) {
                if (prerequisites.add(producer.definition())) requiredInputs.addAll(producer.inputs());
            }
        }
        var phase = new AelisCycleExecutionPlan.Phase(prerequisites, outputs);
        if (phase.equals(previous)) return cycle;
        return new AelisCycleExecutionPlan(cycle.steps(), cycle.minimumSeeds(),
                cycle.protectedKeys(), cycle.seedPolicy(), phase);
    }

    /** Snapshot once so traversal does not repeatedly query an external pattern implementation. */
    static Pattern describe(AEKey definition, IPatternDetails pattern) {
        Objects.requireNonNull(definition, "pattern definition");
        var outputs = new LinkedHashMap<AEKey, Long>();
        for (GenericStack output : pattern.getOutputs()) {
            if (output.amount() > 0) outputs.merge(output.what(), output.amount(), Math::addExact);
        }
        var inputs = new LinkedHashSet<AEKey>();
        var candidates = new LinkedHashSet<>(outputs.keySet());
        var slots = new ArrayList<List<Alternative>>();
        for (var input : pattern.getInputs()) {
            long multiplier = input.getMultiplier();
            if (multiplier <= 0) throw new IllegalArgumentException("Invalid input multiplier");
            var alternatives = new ArrayList<Alternative>();
            for (GenericStack possible : input.getPossibleInputs()) {
                if (possible.amount() <= 0) throw new IllegalArgumentException("Invalid input amount");
                AEKey key = Objects.requireNonNull(possible.what());
                AEKey remainder = input.getRemainingKey(key);
                inputs.add(key);
                if (remainder != null) candidates.add(remainder);
                alternatives.add(new Alternative(key,
                        Math.multiplyExact(possible.amount(), multiplier), remainder, multiplier));
            }
            if (alternatives.isEmpty()) throw new IllegalArgumentException("Missing possible inputs");
            slots.add(alternatives);
        }

        var producedKeys = new LinkedHashSet<AEKey>();
        for (AEKey candidate : candidates) {
            long net = outputs.getOrDefault(candidate, 0L);
            for (var slot : slots) {
                long best = Long.MIN_VALUE;
                for (Alternative alternative : slot) {
                    long returned = candidate.equals(alternative.remainder()) ? alternative.returns() : 0;
                    long consumed = candidate.equals(alternative.key()) ? alternative.amount() : 0;
                    best = Math.max(best, Math.subtractExact(returned, consumed));
                }
                net = Math.addExact(net, best);
            }
            // A returned catalyst is not a producer; a different returned container can be one.
            if (net > 0) producedKeys.add(candidate);
        }
        return new Pattern(definition, Set.copyOf(inputs), Map.copyOf(outputs), Set.copyOf(producedKeys));
    }

    record Pattern(AEKey definition, Set<AEKey> inputs, Map<AEKey, Long> outputs,
            Set<AEKey> producedKeys) {}

    private record Alternative(AEKey key, long amount, AEKey remainder, long returns) {}
}
