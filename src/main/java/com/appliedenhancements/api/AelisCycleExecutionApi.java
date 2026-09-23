package com.appliedenhancements.api;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import com.appliedenhancements.runtime.AelisCycleDispatch;
import com.appliedenhancements.runtime.AelisCycleExecutionNbt;
import com.appliedenhancements.runtime.AelisCyclePatternNormalization;
import com.appliedenhancements.runtime.AelisCyclePhaseAnalysis;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftingTracker;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCycleExecutionPlanCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftingTracker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/** Stable integration helpers for third-party cycle-aware CPU implementations. */
public final class AelisCycleExecutionApi {
    private AelisCycleExecutionApi() {
    }

    /**
     * Returns the plan to submit and use when creating CPU tasks. Cyclic quantity
     * wrappers are normalized, and metadata is preserved. Ordinary plans are unchanged.
     * Reading {@link #getPlan} alone does not normalize the caller's task map.
     */
    public static ICraftingPlan preparePlan(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return requiresCycleAwareCpu(plan) ? copyMetadata(plan, plan) : plan;
    }

    /** Returns an immutable snapshot of the planned cyclic production quantities. */
    public static Map<AEKey, Long> getCyclicCraftAmounts(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        var amounts = plan instanceof AelisCyclicCraftAmountsCarrier carrier
                ? carrier.appliedenhancements$getCyclicCraftAmounts() : null;
        return amounts == null ? Map.of() : Map.copyOf(amounts);
    }

    /**
     * Creates a view for one input-extraction attempt, including every extra batch
     * extraction and rollback belonging to that attempt. Null means dispatch is
     * currently forbidden; a null runtime returns the original inventory.
     * Do not cache this view across runtime advancement or reuse it for another pattern.
     */
    public static @Nullable ICraftingInventory guardInputs(@Nullable AelisCycleRuntimeController runtime,
            AEKey patternDefinition, ICraftingInventory source) {
        Objects.requireNonNull(patternDefinition, "patternDefinition");
        Objects.requireNonNull(source, "source");
        return AelisCycleDispatch.inventory(runtime, patternDefinition, source);
    }

    /**
     * Counts complete current-step firings from already extracted inputs, without
     * advancing the runtime. Non-current/ordinary work returns zero. Every protected
     * input must imply the same positive count within the remaining step.
     * Steps without protected inputs require the caller's own actual dispatch count.
     */
    public static long dispatchedCrafts(@Nullable AelisCycleRuntimeController runtime,
            AEKey patternDefinition, KeyCounter[] inputs) {
        Objects.requireNonNull(patternDefinition, "patternDefinition");
        Objects.requireNonNull(inputs, "inputs");
        for (var input : inputs) Objects.requireNonNull(input, "input holder");
        return AelisCycleDispatch.dispatchedCrafts(runtime, patternDefinition, inputs);
    }

    /** Writes the plan, phase, seed policy, progress and pending returns together. */
    public static CompoundTag writeRuntime(AelisCycleRuntimeController runtime, HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        return writeRuntime(runtime);
    }

    /**
     * Restores supported runtime formats, including older v1 saves. Empty means
     * missing, invalid or unsupported metadata. If a job had saved cycle metadata,
     * do not treat a failed restore as an ordinary job and resume without protection.
     */
    public static Optional<AelisCycleRuntimeController> readRuntime(@Nullable CompoundTag tag,
            HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        return readRuntime(tag);
    }

    /** Forge 1.20.1 AE keys serialize against the static item/fluid registries. */
    public static CompoundTag writeRuntime(AelisCycleRuntimeController runtime) {
        return AelisCycleExecutionNbt.write(Objects.requireNonNull(runtime, "runtime"));
    }

    public static Optional<AelisCycleRuntimeController> readRuntime(@Nullable CompoundTag tag) {
        return Optional.ofNullable(AelisCycleExecutionNbt.read(tag));
    }

    public static Optional<AelisCycleExecutionPlan> getPlan(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof AelisCycleExecutionPlanCarrier carrier) {
            var cycle = carrier.appliedenhancements$getCycleExecutionPlan();
            if (cycle != null && cycle.phase() == null) {
                cycle = AelisCyclePhaseAnalysis.prepare(cycle,
                        AelisCyclePatternNormalization.normalize(plan.patternTimes(), cycle));
            }
            return Optional.ofNullable(cycle);
        }
        return Optional.empty();
    }

    public static boolean requiresCycleAwareCpu(ICraftingPlan plan) {
        return getPlan(plan).isPresent();
    }

    public static boolean supports(ICraftingCPU cpu) {
        return cpu instanceof AelisCycleAwareCpu aware
                && aware.supportsAelisCycleExecution();
    }

    /**
     * Attaches metadata after a successful {@link AelisCraftingPlanner#tryExecute} call.
     * Use the returned plan: custom plan implementations are wrapped without changing their data.
     * The automatic planner configuration does not gate this API.
     */
    public static ICraftingPlan attachToPlan(CraftingSimulationState state, ICraftingPlan plan) {
        Objects.requireNonNull(state, "state");
        if (!(state instanceof AelisCyclicCraftingTracker tracker)) {
            throw new IllegalStateException("AELIS simulation-state integration is not loaded");
        }
        return attach(plan, tracker.appliedenhancements$getCycleExecutionPlan(),
                tracker.appliedenhancements$getCyclicCraftAmounts(),
                ((AelisBigIntegerCraftingTracker) state)
                        .appliedenhancements$getBigIntegerCraftAmounts(),
                ((AelisBigIntegerCraftingTracker) state)
                        .appliedenhancements$getBigIntegerMissingAmounts(),
                ((AelisBigIntegerCraftingTracker) state)
                        .appliedenhancements$getBigIntegerStoredAmounts(),
                ((AelisBigIntegerCraftingTracker) state)
                        .appliedenhancements$getBigIntegerInfiniteAmounts(),
                ((AelisBigIntegerCraftingTracker) state)
                        .appliedenhancements$getBigIntegerPatternTimes(),
                ((AelisBigIntegerCraftingTracker) state).appliedenhancements$getBigIntegerBytes(),
                ((AelisBigIntegerCraftingTracker) state).appliedenhancements$isPreviewOnly(),
                AelisCalculationPath.AELIS);
    }

    /**
     * Copies metadata when replacing a plan without changing its cyclic patterns or firing counts.
     * Use the returned plan, which can wrap a custom {@code target} implementation.
     */
    public static ICraftingPlan copyMetadata(ICraftingPlan source, ICraftingPlan target) {
        Objects.requireNonNull(source, "source");
        var cycle = getPlan(source).orElse(null);
        var amounts = source instanceof AelisCyclicCraftAmountsCarrier carrier
                ? carrier.appliedenhancements$getCyclicCraftAmounts() : Map.<AEKey, Long>of();
        var exactAmounts = source instanceof AelisBigIntegerCraftAmountsCarrier carrier
                ? carrier.appliedenhancements$getBigIntegerCraftAmounts()
                : Map.<AEKey, BigInteger>of();
        var path = source instanceof AelisCalculationPathCarrier carrier
                ? carrier.molecularmanipulator$getCalculationPath() : null;
        if (path == null) {
            path = cycle != null ? AelisCalculationPath.AELIS
                    : source instanceof CraftingPlan ? AelisCalculationPath.AE2_NATIVE
                    : AelisCalculationPath.EXTERNAL;
        }
        var copied = attach(target, cycle,
                amounts == null ? Map.of() : amounts,
                exactAmounts == null ? Map.of() : exactAmounts,
                source instanceof AelisBigIntegerCraftAmountsCarrier exact
                        ? exact.appliedenhancements$getBigIntegerMissingAmounts() : Map.of(),
                source instanceof AelisBigIntegerCraftAmountsCarrier exact
                        ? exact.appliedenhancements$getBigIntegerStoredAmounts() : Map.of(),
                source instanceof AelisBigIntegerCraftAmountsCarrier exact
                        ? exact.appliedenhancements$getBigIntegerInfiniteAmounts() : Map.of(),
                source instanceof AelisBigIntegerCraftAmountsCarrier exact
                        ? exact.appliedenhancements$getBigIntegerPatternTimes() : Map.of(),
                source instanceof AelisBigIntegerCraftAmountsCarrier exact
                        ? exact.appliedenhancements$getBigIntegerBytes() : null,
                source instanceof AelisBigIntegerCraftAmountsCarrier exact && exact.appliedenhancements$isPreviewOnly(),
                path);
        ((AelisBigIntegerCraftAmountsCarrier) copied).appliedenhancements$setBigIntegerFinalAmount(
                source instanceof AelisBigIntegerCraftAmountsCarrier exact ? exact.appliedenhancements$getBigIntegerFinalAmount() : null);
        return copied;
    }

    private static ICraftingPlan attach(ICraftingPlan target, AelisCycleExecutionPlan cycle,
            Map<AEKey, Long> amounts, Map<AEKey, BigInteger> exactAmounts,
            Map<AEKey, BigInteger> exactMissing, Map<AEKey, BigInteger> exactStored,
            Map<AEKey, BigInteger> exactInfinite,
            Map<IPatternDetails, BigInteger> exactPatternTimes,
            BigInteger exactBytes,
            boolean previewOnly,
            AelisCalculationPath path) {
        Objects.requireNonNull(target, "target");
        exactAmounts = overflowingCraftAmounts(exactAmounts);
        exactMissing = overflowingCraftAmounts(exactMissing);
        exactStored = overflowingCraftAmounts(exactStored);
        exactInfinite = positiveAmounts(exactInfinite);
        var normalized = AelisCyclePatternNormalization.normalize(target.patternTimes(), cycle);
        var reconciled = com.appliedenhancements.runtime.ExactScaledTaskReconciliation.reconcile(
                normalized, exactPatternTimes);
        exactPatternTimes = reconciled.exact();
        var patterns = reconciled.projected();
        cycle = AelisCyclePhaseAnalysis.prepare(cycle, patterns);
        if (target instanceof CraftingPlan && patterns != target.patternTimes()) {
            target = new CraftingPlan(target.finalOutput(), target.bytes(), target.simulation(),
                    target.multiplePaths(), target.usedItems(), target.emittedItems(),
                    target.missingItems(), patterns);
        }
        if (target instanceof CraftingPlan
                && target instanceof AelisCycleExecutionPlanCarrier cycles
                && target instanceof AelisCyclicCraftAmountsCarrier totals
                && target instanceof AelisBigIntegerCraftAmountsCarrier exactTotals
                && target instanceof AelisCalculationPathCarrier paths) {
            cycles.appliedenhancements$setCycleExecutionPlan(cycle);
            totals.appliedenhancements$setCyclicCraftAmounts(amounts);
            exactTotals.appliedenhancements$setBigIntegerCraftAmounts(exactAmounts);
            exactTotals.appliedenhancements$setBigIntegerMissingAmounts(exactMissing);
            exactTotals.appliedenhancements$setBigIntegerStoredAmounts(exactStored);
            exactTotals.appliedenhancements$setBigIntegerInfiniteAmounts(exactInfinite);
            exactTotals.appliedenhancements$setBigIntegerPatternTimes(exactPatternTimes);
            exactTotals.appliedenhancements$setBigIntegerBytes(exactBytes);
            exactTotals.appliedenhancements$setPreviewOnly(previewOnly);
            paths.molecularmanipulator$setCalculationPath(path);
            return target;
        }
        return new AttachedPlan(target instanceof AttachedPlan attached ? attached.delegate : target,
                cycle, Map.copyOf(amounts), Map.copyOf(exactAmounts), Map.copyOf(exactMissing),
                Map.copyOf(exactStored), Map.copyOf(exactInfinite),
                Map.copyOf(exactPatternTimes), exactBytes, previewOnly, path, patterns);
    }

    private static Map<AEKey, BigInteger> overflowingCraftAmounts(
            Map<AEKey, BigInteger> amounts) {
        Objects.requireNonNull(amounts, "exactAmounts");
        var result = new LinkedHashMap<AEKey, BigInteger>();
        var longMax = BigInteger.valueOf(Long.MAX_VALUE);
        amounts.forEach((key, amount) -> {
            if (key != null && amount != null && amount.compareTo(longMax) > 0) {
                result.put(key, amount);
            }
        });
        return Map.copyOf(result);
    }

    private static Map<AEKey, BigInteger> positiveAmounts(
            Map<AEKey, BigInteger> amounts) {
        Objects.requireNonNull(amounts, "amounts");
        var result = new LinkedHashMap<AEKey, BigInteger>();
        amounts.forEach((key, amount) -> {
            if (key != null && amount != null && amount.signum() > 0) {
                result.put(key, amount);
            }
        });
        return Map.copyOf(result);
    }

    private static final class AttachedPlan implements ICraftingPlan,
            AelisCycleExecutionPlanCarrier, AelisCyclicCraftAmountsCarrier,
            AelisBigIntegerCraftAmountsCarrier, AelisCalculationPathCarrier {
        private final ICraftingPlan delegate;
        private final Map<IPatternDetails, Long> patterns;
        private AelisCycleExecutionPlan cycle;
        private Map<AEKey, Long> amounts;
        private Map<AEKey, BigInteger> exactAmounts;
        private Map<AEKey, BigInteger> exactMissing;
        private Map<AEKey, BigInteger> exactStored;
        private Map<AEKey, BigInteger> exactInfinite;
        private Map<IPatternDetails, BigInteger> exactPatternTimes;
        private BigInteger exactBytes;
        private BigInteger exactFinalAmount;
        @Override public BigInteger appliedenhancements$getBigIntegerFinalAmount() { return exactFinalAmount; }
        @Override public void appliedenhancements$setBigIntegerFinalAmount(BigInteger value) { exactFinalAmount = value; }
        private boolean previewOnly;
        private AelisCalculationPath path;

        private AttachedPlan(ICraftingPlan delegate, AelisCycleExecutionPlan cycle,
                Map<AEKey, Long> amounts, Map<AEKey, BigInteger> exactAmounts,
                Map<AEKey, BigInteger> exactMissing, Map<AEKey, BigInteger> exactStored,
                Map<AEKey, BigInteger> exactInfinite,
                Map<IPatternDetails, BigInteger> exactPatternTimes,
                BigInteger exactBytes,
                boolean previewOnly,
                AelisCalculationPath path, Map<IPatternDetails, Long> patterns) {
            this.delegate = delegate;
            this.patterns = patterns;
            this.cycle = cycle;
            this.amounts = amounts;
            this.exactAmounts = exactAmounts;
            this.exactMissing = exactMissing;
            this.exactStored = exactStored;
            this.exactInfinite = exactInfinite;
            this.exactPatternTimes = exactPatternTimes;
            this.exactBytes = exactBytes;
            this.previewOnly = previewOnly;
            this.path = path;
        }

        @Override public GenericStack finalOutput() { return delegate.finalOutput(); }
        @Override public long bytes() { return delegate.bytes(); }
        @Override public BigInteger appliedenhancements$getBigIntegerBytes() { return exactBytes; }
        @Override public void appliedenhancements$setBigIntegerBytes(BigInteger value) { exactBytes = value; }
        @Override public boolean simulation() { return delegate.simulation(); }
        @Override public boolean multiplePaths() { return delegate.multiplePaths(); }
        @Override public KeyCounter usedItems() { return delegate.usedItems(); }
        @Override public KeyCounter emittedItems() { return delegate.emittedItems(); }
        @Override public KeyCounter missingItems() { return delegate.missingItems(); }
        @Override public Map<IPatternDetails, Long> patternTimes() { return patterns; }
        @Override public Map<IPatternDetails, BigInteger> appliedenhancements$getBigIntegerPatternTimes() { return exactPatternTimes; }
        @Override public void appliedenhancements$setBigIntegerPatternTimes(Map<IPatternDetails, BigInteger> value) { exactPatternTimes = Map.copyOf(value); }
        @Override public AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan() { return cycle; }
        @Override public void appliedenhancements$setCycleExecutionPlan(AelisCycleExecutionPlan plan) { cycle = plan; }
        @Override public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() { return amounts; }
        @Override public void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> value) { amounts = Map.copyOf(value); }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() { return exactAmounts; }
        @Override public void appliedenhancements$setBigIntegerCraftAmounts(Map<AEKey, BigInteger> value) { exactAmounts = Map.copyOf(value); }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() { return exactMissing; }
        @Override public void appliedenhancements$setBigIntegerMissingAmounts(Map<AEKey, BigInteger> value) { exactMissing = Map.copyOf(value); }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerStoredAmounts() { return exactStored; }
        @Override public void appliedenhancements$setBigIntegerStoredAmounts(Map<AEKey, BigInteger> value) { exactStored = Map.copyOf(value); }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerInfiniteAmounts() { return exactInfinite; }
        @Override public void appliedenhancements$setBigIntegerInfiniteAmounts(Map<AEKey, BigInteger> value) { exactInfinite = Map.copyOf(value); }
        @Override public boolean appliedenhancements$isPreviewOnly() { return previewOnly; }
        @Override public void appliedenhancements$setPreviewOnly(boolean value) { previewOnly = value; }
        @Override public AelisCalculationPath molecularmanipulator$getCalculationPath() { return path; }
        @Override public void molecularmanipulator$setCalculationPath(AelisCalculationPath value) { path = Objects.requireNonNull(value); }
    }
}
