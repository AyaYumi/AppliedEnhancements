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
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCycleExecutionPlanCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftingTracker;
import java.util.Map;
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
        return AelisCycleExecutionNbt.write(Objects.requireNonNull(runtime, "runtime"),
                Objects.requireNonNull(registries, "registries"));
    }

    /**
     * Restores supported runtime formats, including older v1 saves. Empty means
     * missing, invalid or unsupported metadata. If a job had saved cycle metadata,
     * do not treat a failed restore as an ordinary job and resume without protection.
     */
    public static Optional<AelisCycleRuntimeController> readRuntime(@Nullable CompoundTag tag,
            HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        return Optional.ofNullable(AelisCycleExecutionNbt.read(tag, registries));
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
                tracker.appliedenhancements$getCyclicCraftAmounts(), AelisCalculationPath.AELIS);
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
        var path = source instanceof AelisCalculationPathCarrier carrier
                ? carrier.molecularmanipulator$getCalculationPath() : null;
        if (path == null) {
            path = cycle != null ? AelisCalculationPath.AELIS
                    : source instanceof CraftingPlan ? AelisCalculationPath.AE2_NATIVE
                    : AelisCalculationPath.EXTERNAL;
        }
        return attach(target, cycle, amounts == null ? Map.of() : amounts, path);
    }

    private static ICraftingPlan attach(ICraftingPlan target, AelisCycleExecutionPlan cycle,
            Map<AEKey, Long> amounts, AelisCalculationPath path) {
        Objects.requireNonNull(target, "target");
        var patterns = AelisCyclePatternNormalization.normalize(target.patternTimes(), cycle);
        cycle = AelisCyclePhaseAnalysis.prepare(cycle, patterns);
        if (target instanceof CraftingPlan && patterns != target.patternTimes()) {
            target = new CraftingPlan(target.finalOutput(), target.bytes(), target.simulation(),
                    target.multiplePaths(), target.usedItems(), target.emittedItems(),
                    target.missingItems(), patterns);
        }
        if (target instanceof CraftingPlan
                && target instanceof AelisCycleExecutionPlanCarrier cycles
                && target instanceof AelisCyclicCraftAmountsCarrier totals
                && target instanceof AelisCalculationPathCarrier paths) {
            cycles.appliedenhancements$setCycleExecutionPlan(cycle);
            totals.appliedenhancements$setCyclicCraftAmounts(amounts);
            paths.molecularmanipulator$setCalculationPath(path);
            return target;
        }
        return new AttachedPlan(target instanceof AttachedPlan attached ? attached.delegate : target,
                cycle, Map.copyOf(amounts), path, patterns);
    }

    private static final class AttachedPlan implements ICraftingPlan,
            AelisCycleExecutionPlanCarrier, AelisCyclicCraftAmountsCarrier, AelisCalculationPathCarrier {
        private final ICraftingPlan delegate;
        private final Map<IPatternDetails, Long> patterns;
        private AelisCycleExecutionPlan cycle;
        private Map<AEKey, Long> amounts;
        private AelisCalculationPath path;

        private AttachedPlan(ICraftingPlan delegate, AelisCycleExecutionPlan cycle,
                Map<AEKey, Long> amounts, AelisCalculationPath path, Map<IPatternDetails, Long> patterns) {
            this.delegate = delegate;
            this.patterns = patterns;
            this.cycle = cycle;
            this.amounts = amounts;
            this.path = path;
        }

        @Override public GenericStack finalOutput() { return delegate.finalOutput(); }
        @Override public long bytes() { return delegate.bytes(); }
        @Override public boolean simulation() { return delegate.simulation(); }
        @Override public boolean multiplePaths() { return delegate.multiplePaths(); }
        @Override public KeyCounter usedItems() { return delegate.usedItems(); }
        @Override public KeyCounter emittedItems() { return delegate.emittedItems(); }
        @Override public KeyCounter missingItems() { return delegate.missingItems(); }
        @Override public Map<IPatternDetails, Long> patternTimes() { return patterns; }
        @Override public AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan() { return cycle; }
        @Override public void appliedenhancements$setCycleExecutionPlan(AelisCycleExecutionPlan plan) { cycle = plan; }
        @Override public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() { return amounts; }
        @Override public void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> value) { amounts = Map.copyOf(value); }
        @Override public AelisCalculationPath molecularmanipulator$getCalculationPath() { return path; }
        @Override public void molecularmanipulator$setCalculationPath(AelisCalculationPath value) { path = Objects.requireNonNull(value); }
    }
}
