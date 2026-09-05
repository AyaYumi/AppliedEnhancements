package com.appliedenhancements.api;

import appeng.api.stacks.AEKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CPU-independent execution metadata for an AELIS cyclic crafting plan.
 */
public record AelisCycleExecutionPlan(
        List<Step> steps,
        Map<AEKey, Long> minimumSeeds,
        Set<AEKey> protectedKeys,
        AelisCycleSeedPolicy seedPolicy,
        Phase phase) {
    /**
     * Binary-compatible constructor for integrations built before seed policies.
     * It retains the former maximum-throughput behavior.
     */
    public AelisCycleExecutionPlan(
            List<Step> steps,
            Map<AEKey, Long> minimumSeeds,
            Set<AEKey> protectedKeys) {
        this(steps, minimumSeeds, protectedKeys,
                AelisCycleSeedPolicy.MAX_THROUGHPUT, null);
    }

    /** Keeps existing integrations concurrent until their complete task graph is attached. */
    public AelisCycleExecutionPlan(
            List<Step> steps,
            Map<AEKey, Long> minimumSeeds,
            Set<AEKey> protectedKeys,
            AelisCycleSeedPolicy seedPolicy) {
        this(steps, minimumSeeds, protectedKeys, seedPolicy, null);
    }

    public AelisCycleExecutionPlan {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Cycle execution plan requires at least one step");
        }
        minimumSeeds = positiveMap(minimumSeeds, "minimumSeeds");
        protectedKeys = Set.copyOf(
                Objects.requireNonNull(protectedKeys, "protectedKeys"));
        Objects.requireNonNull(seedPolicy, "seedPolicy");
        if (protectedKeys.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("protectedKeys must not contain null");
        }
        for (Step step : steps) {
            if (!protectedKeys.containsAll(step.inputsPerCraft().keySet())) {
                throw new IllegalArgumentException(
                        "Every protected cycle input must be listed in protectedKeys");
            }
            if (phase != null && !phase.outputsPerPattern().containsKey(step.patternDefinition())) {
                throw new IllegalArgumentException(
                        "Cycle phase must describe the outputs of every scheduled pattern");
            }
        }
        if (!protectedKeys.containsAll(minimumSeeds.keySet())) {
            throw new IllegalArgumentException(
                    "Every minimum seed must be listed in protectedKeys");
        }
    }

    public Set<AEKey> patternDefinitions() {
        var result = new LinkedHashSet<AEKey>();
        for (Step step : steps) {
            result.add(step.patternDefinition());
        }
        return Set.copyOf(result);
    }

    /** Ordinary prerequisites and actual outputs needed to finish the cyclic phase. */
    public record Phase(
            Set<AEKey> prerequisitePatterns,
            Map<AEKey, Map<AEKey, Long>> outputsPerPattern) {
        public Phase {
            prerequisitePatterns = Set.copyOf(
                    Objects.requireNonNull(prerequisitePatterns, "prerequisitePatterns"));
            Objects.requireNonNull(outputsPerPattern, "outputsPerPattern");
            var outputs = new LinkedHashMap<AEKey, Map<AEKey, Long>>();
            for (var entry : outputsPerPattern.entrySet()) {
                outputs.put(Objects.requireNonNull(entry.getKey(), "output pattern"),
                        positiveMap(entry.getValue(), "pattern outputs"));
            }
            outputsPerPattern = Map.copyOf(outputs);
        }
    }

    public record Step(
            AEKey patternDefinition,
            long crafts,
            Map<AEKey, Long> inputsPerCraft,
            Set<AEKey> selfReplenishingInputs) {
        public Step {
            Objects.requireNonNull(patternDefinition, "patternDefinition");
            if (crafts <= 0) {
                throw new IllegalArgumentException("Step crafts must be positive");
            }
            inputsPerCraft = positiveMap(inputsPerCraft, "inputsPerCraft");
            selfReplenishingInputs = Set.copyOf(
                    Objects.requireNonNull(
                            selfReplenishingInputs, "selfReplenishingInputs"));
            if (!inputsPerCraft.keySet().containsAll(selfReplenishingInputs)) {
                throw new IllegalArgumentException(
                        "Self-replenishing inputs must also be cycle inputs");
            }
        }
    }

    private static Map<AEKey, Long> positiveMap(
            Map<AEKey, Long> values, String label) {
        Objects.requireNonNull(values, label);
        var result = new LinkedHashMap<AEKey, Long>();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue() <= 0) {
                throw new IllegalArgumentException(
                        label + " must use non-null keys and positive values");
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(result);
    }
}
