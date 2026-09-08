package com.appliedenhancements.api;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.util.SaturatingLongMath;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Stateful, CPU-independent scheduler for an {@link AelisCycleExecutionPlan}. */
public final class AelisCycleRuntimeController {
    private final AelisCycleExecutionPlan plan;
    private final Set<AEKey> cyclePatterns;
    private final Map<AEKey, Long> pendingOutputs = new LinkedHashMap<>();
    private int stepIndex;
    private long remainingCrafts;

    /**
     * Preserves the original API behavior for CPUs that do not report returned outputs.
     * Use {@link #withCyclePhase(AelisCycleExecutionPlan)} for the complete phase protocol.
     */
    public AelisCycleRuntimeController(AelisCycleExecutionPlan plan) {
        this(plan, new State(0, plan.steps().get(0).crafts()), false);
    }

    /** Opts into prerequisite scheduling and waiting for the cyclic outputs to return. */
    public static AelisCycleRuntimeController withCyclePhase(AelisCycleExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return withCyclePhase(plan, new State(0, plan.steps().get(0).crafts()));
    }

    /** Restores the complete phase protocol, including outputs that are still in flight. */
    public static AelisCycleRuntimeController withCyclePhase(
            AelisCycleExecutionPlan plan, State state) {
        return new AelisCycleRuntimeController(plan, state, true);
    }

    private static AelisCycleExecutionPlan withoutPhase(AelisCycleExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return plan.phase() == null ? plan : new AelisCycleExecutionPlan(
                plan.steps(), plan.minimumSeeds(), plan.protectedKeys(), plan.seedPolicy());
    }

    /** Restores legacy scheduling without enabling the newer phase or output ledger. */
    public AelisCycleRuntimeController(
            AelisCycleExecutionPlan plan, State state) {
        this(plan, state, false);
    }

    private AelisCycleRuntimeController(
            AelisCycleExecutionPlan plan, State state, boolean phaseAware) {
        Objects.requireNonNull(plan, "plan");
        this.plan = phaseAware ? plan : withoutPhase(plan);
        Objects.requireNonNull(state, "state");
        if (state.stepIndex() < 0 || state.stepIndex() > plan.steps().size()) {
            throw new IllegalArgumentException("Invalid cycle runtime step index");
        }
        this.cyclePatterns = plan.patternDefinitions();
        this.stepIndex = state.stepIndex();
        if (stepIndex == plan.steps().size()) {
            if (state.remainingCrafts() != 0) {
                throw new IllegalArgumentException(
                        "Completed cycle runtime cannot have remaining crafts");
            }
            this.remainingCrafts = 0;
        } else {
            long maximum = plan.steps().get(stepIndex).crafts();
            if (state.remainingCrafts() <= 0 || state.remainingCrafts() > maximum) {
                throw new IllegalArgumentException("Invalid remaining cycle crafts");
            }
            this.remainingCrafts = state.remainingCrafts();
        }
        if (phaseAware) {
            validatePendingOutputs(plan, state);
            this.pendingOutputs.putAll(state.pendingOutputs());
        }
    }

    private static void validatePendingOutputs(AelisCycleExecutionPlan plan, State state) {
        if (state.pendingOutputs().isEmpty()) return;
        if (plan.phase() == null) {
            throw new IllegalArgumentException("Pending cycle outputs require phase metadata");
        }
        var maximumOutputs = new LinkedHashMap<AEKey, Long>();
        for (int index = 0; index < plan.steps().size() && index <= state.stepIndex(); index++) {
            var step = plan.steps().get(index);
            long dispatched = index < state.stepIndex()
                    ? step.crafts() : step.crafts() - state.remainingCrafts();
            if (dispatched <= 0) continue;
            for (var output : plan.phase().outputsPerPattern().get(step.patternDefinition()).entrySet()) {
                maximumOutputs.merge(output.getKey(),
                        SaturatingLongMath.multiply(output.getValue(), dispatched), SaturatingLongMath::add);
            }
        }
        for (var output : state.pendingOutputs().entrySet()) {
            if (output.getValue() > maximumOutputs.getOrDefault(output.getKey(), 0L)) {
                throw new IllegalArgumentException("Pending cycle output exceeds dispatched production");
            }
        }
    }

    public AelisCycleExecutionPlan plan() {
        return plan;
    }

    public Optional<AelisCycleExecutionPlan.Step> currentStep() {
        return isComplete()
                ? Optional.empty()
                : Optional.of(plan.steps().get(stepIndex));
    }

    public long remainingCrafts() {
        return remainingCrafts;
    }

    public boolean isComplete() {
        return stepIndex >= plan.steps().size();
    }

    /** A phase finishes after its last dispatch and the return of its tracked outputs. */
    public boolean isCyclePhaseComplete() {
        return isComplete() && pendingOutputs.isEmpty();
    }

    public boolean hasActiveSeedProtection() {
        return !isCyclePhaseComplete()
                || plan.seedPolicy() == AelisCycleSeedPolicy.PRESERVE_MINIMUM
                && !plan.minimumSeeds().isEmpty();
    }

    public boolean isCyclePattern(AEKey patternDefinition) {
        return cyclePatterns.contains(patternDefinition);
    }

    public boolean canDispatch(
            AEKey patternDefinition, Set<AEKey> possibleInputKeys) {
        Objects.requireNonNull(patternDefinition, "patternDefinition");
        Objects.requireNonNull(possibleInputKeys, "possibleInputKeys");
        if (isCyclePhaseComplete()) {
            // AE2 merges task counts by pattern definition. Any occurrences
            // beyond the proven cyclic schedule are ordinary work and may run
            // once the schedule is complete; the CPU still enforces the seed
            // floor after extracting their actual inputs.
            return true;
        }
        if (!isComplete() && plan.steps().get(stepIndex).patternDefinition()
                .equals(patternDefinition)) {
            return true;
        }
        if (cyclePatterns.contains(patternDefinition)) {
            return false;
        }
        return plan.phase() == null
                || plan.phase().prerequisitePatterns().contains(patternDefinition);
    }

    public void patternDispatched(AEKey patternDefinition, long crafts) {
        Objects.requireNonNull(patternDefinition, "patternDefinition");
        if (isComplete()
                || !plan.steps().get(stepIndex).patternDefinition()
                        .equals(patternDefinition)) {
            throw new IllegalStateException("Dispatched pattern is not the active cycle step");
        }
        if (crafts <= 0 || crafts > remainingCrafts) {
            throw new IllegalArgumentException("Invalid dispatched cycle craft count");
        }
        if (plan.phase() != null) {
            for (var output : plan.phase().outputsPerPattern()
                    .getOrDefault(patternDefinition, Map.of()).entrySet()) {
                pendingOutputs.merge(output.getKey(),
                        SaturatingLongMath.multiply(output.getValue(), crafts),
                        SaturatingLongMath::add);
            }
        }
        remainingCrafts -= crafts;
        if (remainingCrafts == 0) {
            stepIndex++;
            remainingCrafts = isComplete()
                    ? 0
                    : plan.steps().get(stepIndex).crafts();
        }
    }

    /** Records the decrease in outstanding output after a real CPU insertion settles it. */
    public void recordReturned(AEKey key, long accepted) {
        Objects.requireNonNull(key, "key");
        if (accepted < 0) {
            throw new IllegalArgumentException("Returned cycle amount must be non-negative");
        }
        if (accepted == 0) {
            return;
        }
        pendingOutputs.computeIfPresent(key,
                (ignored, pending) -> accepted >= pending ? null : pending - accepted);
    }

    public long requiredRetainedAmount(AEKey key) {
        Objects.requireNonNull(key, "key");
        long required = plan.seedPolicy() == AelisCycleSeedPolicy.PRESERVE_MINIMUM
                ? plan.minimumSeeds().getOrDefault(key, 0L)
                : 0;
        if (isComplete()) {
            return required;
        }
        for (int index = stepIndex; index < plan.steps().size(); index++) {
            AelisCycleExecutionPlan.Step step = plan.steps().get(index);
            Long perCraft = step.inputsPerCraft().get(key);
            if (perCraft == null) {
                continue;
            }
            long crafts = index == stepIndex ? remainingCrafts : step.crafts();
            long stepRequired = step.selfReplenishingInputs().contains(key)
                    ? perCraft
                    : SaturatingLongMath.multiply(perCraft, crafts);
            required = Math.max(required, stepRequired);
        }
        return required;
    }

    public long maximumConsumableAmount(AEKey key, long currentlyStored) {
        if (currentlyStored < 0) {
            throw new IllegalArgumentException("Stored cycle amount must be non-negative");
        }
        return Math.max(0, currentlyStored - requiredRetainedAmount(key));
    }

    public long amountToRetain(
            AEKey key, long incomingAmount, long currentlyStored) {
        if (incomingAmount < 0 || currentlyStored < 0) {
            throw new IllegalArgumentException(
                    "Incoming and stored cycle amounts must be non-negative");
        }
        Objects.requireNonNull(key, "key");
        long remainingInputs = 0;
        for (int index = stepIndex; index < plan.steps().size(); index++) {
            var step = plan.steps().get(index);
            long crafts = index == stepIndex ? remainingCrafts : step.crafts();
            remainingInputs = SaturatingLongMath.add(remainingInputs,
                    SaturatingLongMath.multiply(
                            step.inputsPerCraft().getOrDefault(key, 0L), crafts));
        }
        long seedFloor = plan.seedPolicy() == AelisCycleSeedPolicy.PRESERVE_MINIMUM
                ? plan.minimumSeeds().getOrDefault(key, 0L) : 0;
        long deficit = Math.max(0, Math.max(seedFloor, remainingInputs) - currentlyStored);
        return Math.min(incomingAmount, deficit);
    }

    public State snapshot() {
        return new State(stepIndex, remainingCrafts, pendingOutputs);
    }

    public record State(int stepIndex, long remainingCrafts, Map<AEKey, Long> pendingOutputs) {
        public State(int stepIndex, long remainingCrafts) {
            this(stepIndex, remainingCrafts, Map.of());
        }

        public State {
            Objects.requireNonNull(pendingOutputs, "pendingOutputs");
            for (var entry : pendingOutputs.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    throw new IllegalArgumentException("Pending cycle outputs must be positive");
                }
            }
            pendingOutputs = Map.copyOf(pendingOutputs);
        }
    }
}
