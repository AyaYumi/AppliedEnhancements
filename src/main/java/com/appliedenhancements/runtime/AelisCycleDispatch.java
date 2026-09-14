package com.appliedenhancements.runtime;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.appliedenhancements.util.SaturatingLongMath;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Inventory views keep both single and batched dispatches inside the current cycle step. */
public final class AelisCycleDispatch {
    private AelisCycleDispatch() {
    }

    public static ICraftingInventory inventory(AelisCycleRuntimeController runtime,
            AEKey patternDefinition, ICraftingInventory source) {
        if (runtime == null) {
            return source;
        }
        if (!runtime.canDispatch(patternDefinition, Set.of())) {
            return null;
        }
        var current = runtime.currentStep()
                .filter(step -> step.patternDefinition().equals(patternDefinition))
                .orElse(null);
        return new ICraftingInventory() {
            private final Map<AEKey, Long> extracted = new HashMap<>();

            @Override
            public void insert(AEKey key, long amount, Actionable mode) {
                source.insert(key, amount, mode);
                if (mode == Actionable.MODULATE) {
                    extracted.computeIfPresent(key, (ignored, value) -> Math.max(0, value - amount));
                }
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode) {
                long available = source.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
                long maximum;
                if (current == null) {
                    maximum = runtime.maximumConsumableAmount(key, available);
                } else {
                    Long perCraft = current.inputsPerCraft().get(key);
                    maximum = perCraft == null ? available : Math.min(available, Math.max(0,
                            SaturatingLongMath.multiply(perCraft, runtime.remainingCrafts())
                                    - extracted.getOrDefault(key, 0L)));
                }
                long result = source.extract(key, Math.min(amount, maximum), mode);
                if (mode == Actionable.MODULATE && result > 0) {
                    extracted.merge(key, result, SaturatingLongMath::add);
                }
                return result;
            }

            @Override
            public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
                return source.findFuzzyTemplates(key);
            }
        };
    }

    public static long dispatchedCrafts(AelisCycleRuntimeController runtime,
            AEKey patternDefinition, KeyCounter[] inputs) {
        if (runtime == null || runtime.currentStep()
                .filter(step -> step.patternDefinition().equals(patternDefinition)).isEmpty()) {
            return 0;
        }
        var requiredInputs = runtime.currentStep().orElseThrow().inputsPerCraft();
        if (requiredInputs.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot infer cyclic dispatch count without protected inputs; actual pattern inputs are required");
        }
        Objects.requireNonNull(inputs, "inputs");
        var totals = new KeyCounter();
        for (var input : inputs) {
            totals.addAll(Objects.requireNonNull(input, "input holder"));
        }
        long crafts = -1;
        for (var input : requiredInputs.entrySet()) {
            long amount = totals.get(input.getKey());
            if (amount % input.getValue() != 0) {
                throw new IllegalStateException("Cyclic dispatch has a partial recipe input");
            }
            long inputCrafts = amount / input.getValue();
            if (crafts >= 0 && crafts != inputCrafts) {
                throw new IllegalStateException("Cyclic dispatch inputs represent different craft counts");
            }
            crafts = inputCrafts;
        }
        if (crafts <= 0 || crafts > runtime.remainingCrafts()) {
            throw new IllegalStateException("Cyclic dispatch exceeds the current execution step");
        }
        return crafts;
    }

    /**
     * Counts one native AE2 provider push, including patterns without protected inputs.
     * Native AE2 calls pushPattern once per task; scaled pushes still expose protected
     * inputs and therefore use the strict aggregate-input calculation above.
     */
    public static long dispatchedProviderPush(AelisCycleRuntimeController runtime,
            AEKey patternDefinition, KeyCounter[] inputs) {
        Objects.requireNonNull(patternDefinition, "patternDefinition");
        Objects.requireNonNull(inputs, "inputs");
        for (var input : inputs) {
            Objects.requireNonNull(input, "input holder");
        }
        if (runtime == null || runtime.currentStep()
                .filter(step -> step.patternDefinition().equals(patternDefinition)).isEmpty()) {
            return 0;
        }
        if (runtime.currentStep().orElseThrow().inputsPerCraft().isEmpty()) {
            return 1;
        }
        return dispatchedCrafts(runtime, patternDefinition, inputs);
    }
}
