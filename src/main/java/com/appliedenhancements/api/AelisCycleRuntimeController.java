package com.appliedenhancements.api;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.util.SaturatingLongMath;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Stateful, CPU-independent scheduler for an {@link AelisCycleExecutionPlan}. */
public final class AelisCycleRuntimeController {
    private final AelisCycleExecutionPlan plan;
    private final Set<AEKey> cyclePatterns;
    private final Map<AEKey, BigInteger> pendingOutputs = new LinkedHashMap<>();
    private int stepIndex;
    private BigInteger remainingCrafts;

    /**
     * Preserves the original API behavior for CPUs that do not report returned outputs.
     * Use {@link #withCyclePhase(AelisCycleExecutionPlan)} for the complete phase protocol.
     */
    public AelisCycleRuntimeController(AelisCycleExecutionPlan plan) {
        this(plan, new State(0, plan.steps().get(0).exactCrafts(), Map.of()), false);
    }

    /** Opts into prerequisite scheduling and waiting for the cyclic outputs to return. */
    public static AelisCycleRuntimeController withCyclePhase(AelisCycleExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return withCyclePhase(plan, new State(0, plan.steps().get(0).exactCrafts(), Map.of()));
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
            if (state.exactRemainingCrafts().signum() != 0) {
                throw new IllegalArgumentException(
                        "Completed cycle runtime cannot have remaining crafts");
            }
            this.remainingCrafts = BigInteger.ZERO;
        } else {
            BigInteger maximum = plan.steps().get(stepIndex).exactCrafts();
            if (state.exactRemainingCrafts().signum() <= 0
                    || state.exactRemainingCrafts().compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Invalid remaining cycle crafts");
            }
            this.remainingCrafts = state.exactRemainingCrafts();
        }
        if (phaseAware) {
            validatePendingOutputs(plan, state);
            this.pendingOutputs.putAll(state.exactPendingOutputs());
        }
    }

    private static void validatePendingOutputs(AelisCycleExecutionPlan plan, State state) {
        if (state.pendingOutputs().isEmpty()) return;
        if (plan.phase() == null) {
            throw new IllegalArgumentException("Pending cycle outputs require phase metadata");
        }
        var maximumOutputs = new LinkedHashMap<AEKey, BigInteger>();
        for (int index = 0; index < plan.steps().size() && index <= state.stepIndex(); index++) {
            var step = plan.steps().get(index);
            BigInteger dispatched = index < state.stepIndex()
                    ? step.exactCrafts() : step.exactCrafts().subtract(state.exactRemainingCrafts());
            if (dispatched.signum() <= 0) continue;
            for (var output : plan.phase().outputsPerPattern().get(step.patternDefinition()).entrySet()) {
                maximumOutputs.merge(output.getKey(),
                        BigInteger.valueOf(output.getValue()).multiply(dispatched), BigInteger::add);
            }
        }
        for (var output : state.exactPendingOutputs().entrySet()) {
            if (output.getValue().compareTo(maximumOutputs.getOrDefault(output.getKey(), BigInteger.ZERO)) > 0) {
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
        return project(remainingCrafts);
    }

    public BigInteger exactRemainingCrafts() {
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
        patternDispatched(patternDefinition, BigInteger.valueOf(crafts));
    }

    public void patternDispatched(AEKey patternDefinition, BigInteger crafts) {
        Objects.requireNonNull(patternDefinition, "patternDefinition");
        Objects.requireNonNull(crafts, "crafts");
        if (isComplete()
                || !plan.steps().get(stepIndex).patternDefinition()
                        .equals(patternDefinition)) {
            throw new IllegalStateException("Dispatched pattern is not the active cycle step");
        }
        if (crafts.signum() <= 0 || crafts.compareTo(remainingCrafts) > 0) {
            throw new IllegalArgumentException("Invalid dispatched cycle craft count");
        }
        if (plan.phase() != null) {
            for (var output : plan.phase().outputsPerPattern()
                    .getOrDefault(patternDefinition, Map.of()).entrySet()) {
                pendingOutputs.merge(output.getKey(),
                        BigInteger.valueOf(output.getValue()).multiply(crafts),
                        BigInteger::add);
            }
        }
        remainingCrafts = remainingCrafts.subtract(crafts);
        if (remainingCrafts.signum() == 0) {
            stepIndex++;
            remainingCrafts = isComplete()
                    ? BigInteger.ZERO
                    : plan.steps().get(stepIndex).exactCrafts();
        }
    }

    /** Records the decrease in outstanding output after a real CPU insertion settles it. */
    public void recordReturned(AEKey key, long accepted) {
        recordReturned(key, BigInteger.valueOf(accepted));
    }

    public void recordReturned(AEKey key, BigInteger accepted) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(accepted, "accepted");
        if (accepted.signum() < 0) {
            throw new IllegalArgumentException("Returned cycle amount must be non-negative");
        }
        if (accepted.signum() == 0) {
            return;
        }
        pendingOutputs.computeIfPresent(key,
                (ignored, pending) -> accepted.compareTo(pending) >= 0 ? null : pending.subtract(accepted));
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
            BigInteger crafts = index == stepIndex ? remainingCrafts : step.exactCrafts();
            long stepRequired = step.selfReplenishingInputs().contains(key)
                    ? perCraft
                    : project(BigInteger.valueOf(perCraft).multiply(crafts));
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
            BigInteger crafts = index == stepIndex ? remainingCrafts : step.exactCrafts();
            remainingInputs = SaturatingLongMath.add(remainingInputs,
                    project(BigInteger.valueOf(
                            step.inputsPerCraft().getOrDefault(key, 0L)).multiply(crafts)));
        }
        long seedFloor = plan.seedPolicy() == AelisCycleSeedPolicy.PRESERVE_MINIMUM
                ? plan.minimumSeeds().getOrDefault(key, 0L) : 0;
        long deficit = Math.max(0, Math.max(seedFloor, remainingInputs) - currentlyStored);
        return Math.min(incomingAmount, deficit);
    }

    public State snapshot() {
        return new State(stepIndex, remainingCrafts, pendingOutputs);
    }

    /** Restores a rejected dispatch in place so its shared extraction view remains valid. */
    public void restore(State state) {
        var validated = new AelisCycleRuntimeController(plan, state, true);
        stepIndex = validated.stepIndex;
        remainingCrafts = validated.remainingCrafts;
        pendingOutputs.clear();
        pendingOutputs.putAll(validated.pendingOutputs);
    }

    private static long project(BigInteger amount) {
        return amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    private static Map<AEKey, Long> projectAmounts(Map<AEKey, BigInteger> amounts) {
        var projected = new LinkedHashMap<AEKey, Long>();
        amounts.forEach((key, amount) -> projected.put(key, project(amount)));
        return Map.copyOf(projected);
    }

    private static Map<AEKey, BigInteger> exactAmounts(Map<AEKey, Long> amounts) {
        var exact = new LinkedHashMap<AEKey, BigInteger>();
        amounts.forEach((key, amount) -> exact.put(key, BigInteger.valueOf(amount)));
        return Map.copyOf(exact);
    }

    public record State(int stepIndex, long remainingCrafts, Map<AEKey, Long> pendingOutputs,
            BigInteger exactRemainingCrafts, Map<AEKey, BigInteger> exactPendingOutputs) {
        public State(int stepIndex, long remainingCrafts) {
            this(stepIndex, remainingCrafts, Map.of());
        }

        /** Retains the constructor used by existing CPU integrations. */
        public State(int stepIndex, long remainingCrafts, Map<AEKey, Long> pendingOutputs) {
            this(stepIndex, remainingCrafts, pendingOutputs,
                    BigInteger.valueOf(remainingCrafts), exactAmounts(pendingOutputs));
        }

        public State(int stepIndex, BigInteger remainingCrafts, Map<AEKey, BigInteger> pendingOutputs) {
            this(stepIndex, project(remainingCrafts), projectAmounts(pendingOutputs),
                    remainingCrafts, pendingOutputs);
        }

        public State {
            Objects.requireNonNull(pendingOutputs, "pendingOutputs");
            Objects.requireNonNull(exactRemainingCrafts, "exactRemainingCrafts");
            Objects.requireNonNull(exactPendingOutputs, "exactPendingOutputs");
            if (remainingCrafts != project(exactRemainingCrafts)
                    || !pendingOutputs.equals(projectAmounts(exactPendingOutputs))) {
                throw new IllegalArgumentException("Cycle state projections must match exact amounts");
            }
            for (var entry : exactPendingOutputs.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().signum() <= 0) {
                    throw new IllegalArgumentException("Pending cycle outputs must be positive");
                }
            }
            pendingOutputs = Map.copyOf(pendingOutputs);
            exactPendingOutputs = Map.copyOf(exactPendingOutputs);
        }
    }
}
