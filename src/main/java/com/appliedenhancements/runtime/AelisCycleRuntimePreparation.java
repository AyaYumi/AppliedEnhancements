package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.ExecutingCraftingJob;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.appliedenhancements.mixin.ExecutingCraftingJobCycleAccessor;
import com.appliedenhancements.util.SaturatingLongMath;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.level.Level;

/** Adds phase metadata to older CPU saves without changing their tasks, inventory or progress. */
public final class AelisCycleRuntimePreparation {
    private AelisCycleRuntimePreparation() {}

    public static AelisCycleRuntimeController prepare(
            AelisCycleRuntimeController runtime, Map<IPatternDetails, Long> tasks,
            KeyCounter waitingFor, Level level) {
        if (runtime == null || runtime.plan().phase() != null) return runtime;
        try {
            Objects.requireNonNull(tasks, "tasks");
            Objects.requireNonNull(waitingFor, "waitingFor");
            var normalized = AelisCyclePatternNormalization.normalize(tasks, runtime.plan());
            var prepared = AelisCyclePhaseAnalysis.prepare(runtime.plan(), normalized,
                    definition -> definition instanceof AEItemKey item && level != null
                            ? PatternDetailsHelper.decodePattern(item, level) : null);
            if (prepared.phase() == null) return runtime;

            var state = runtime.snapshot();
            var pending = new LinkedHashMap<>(state.pendingOutputs());
            if (pending.isEmpty()) {
                pending.putAll(recoverPending(prepared, state, normalized, waitingFor));
            }
            return AelisCycleRuntimeController.withCyclePhase(prepared, new AelisCycleRuntimeController.State(
                    state.stepIndex(), state.remainingCrafts(), pending));
        } catch (RuntimeException unavailable) {
            AppliedEnhancements.LOGGER.warn(
                    "Could not prepare saved AELIS cycle phase; previous runtime retained", unavailable);
            return runtime;
        }
    }

    /** Native task progress is package-private in AE2, so read it without linking its type. */
    public static AelisCycleRuntimeController prepareNative(
            AelisCycleRuntimeController runtime, ExecutingCraftingJob job, Level level) {
        if (runtime == null || runtime.plan().phase() != null || job == null) return runtime;
        try {
            var access = (ExecutingCraftingJobCycleAccessor) job;
            return prepare(runtime, taskCounts(access.appliedenhancements$getTasks()),
                    access.appliedenhancements$getWaitingFor().list, level);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            AppliedEnhancements.LOGGER.warn(
                    "Could not inspect saved AE2 cycle phase; previous runtime retained", unavailable);
            return runtime;
        }
    }

    static Map<IPatternDetails, Long> taskCounts(Map<IPatternDetails, ?> tasks)
            throws ReflectiveOperationException {
        var counts = new LinkedHashMap<IPatternDetails, Long>();
        for (var entry : tasks.entrySet()) {
            Object progress = entry.getValue();
            if (progress instanceof Number count) {
                counts.put(entry.getKey(), count.longValue());
            } else {
                Field value = progress.getClass().getDeclaredField("value");
                value.setAccessible(true);
                counts.put(entry.getKey(), value.getLong(progress));
            }
        }
        return counts;
    }

    private static Map<AEKey, Long> recoverPending(
            AelisCycleExecutionPlan plan, AelisCycleRuntimeController.State state,
            Map<IPatternDetails, Long> tasks, KeyCounter waitingFor) {
        var cycleDefinitions = plan.patternDefinitions();
        var ambiguousOutputs = new LinkedHashSet<AEKey>();
        for (var pattern : tasks.keySet()) {
            if (cycleDefinitions.contains(pattern.getDefinition())) continue;
            for (var output : pattern.getOutputs()) ambiguousOutputs.add(output.what());
            for (var input : pattern.getInputs()) {
                for (var possible : input.getPossibleInputs()) {
                    var remainder = input.getRemainingKey(possible.what());
                    if (remainder != null) ambiguousOutputs.add(remainder);
                }
            }
        }

        // A v1 save has no production provenance. Limit the barrier to output that a
        // dispatched cyclic step can explain; known ordinary outputs remain unclassified.
        var dispatchedOutputs = new LinkedHashMap<AEKey, Long>();
        for (int index = 0; index < plan.steps().size() && index <= state.stepIndex(); index++) {
            var step = plan.steps().get(index);
            long dispatched = index < state.stepIndex()
                    ? step.crafts() : step.crafts() - state.remainingCrafts();
            if (dispatched <= 0) continue;
            for (var output : plan.phase().outputsPerPattern()
                    .getOrDefault(step.patternDefinition(), Map.of()).entrySet()) {
                if (!ambiguousOutputs.contains(output.getKey())) {
                    dispatchedOutputs.merge(output.getKey(),
                            SaturatingLongMath.multiply(output.getValue(), dispatched),
                            SaturatingLongMath::add);
                }
            }
        }
        var pending = new LinkedHashMap<AEKey, Long>();
        dispatchedOutputs.forEach((key, maximum) -> {
            long amount = Math.min(maximum, waitingFor.get(key));
            if (amount > 0) pending.put(key, amount);
        });
        return pending;
    }
}
