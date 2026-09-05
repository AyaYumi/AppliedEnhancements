package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.github.appliedenhancements.crafting.aelis.AelisLegacyCycleRecovery;
import com.github.appliedenhancements.integration.ae2.AelisScaledPattern;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.Level;

/** Optional AdvancedAE access stays isolated from installations without that mod. */
public final class AdvancedAeCycleRecovery {
    private AdvancedAeCycleRecovery() {}

    public static boolean isOutputComplete(Object logic) {
        try {
            Object job = read(logic, "job");
            return job != null && (Long) read(job, "remainingAmount") == 0;
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("Could not inspect quantum CPU completion", unavailable);
        }
    }

    /** Completes older phase metadata after any task normalization or legacy recovery. */
    public static AelisCycleRuntimeController prepare(
            Object logic, AelisCycleRuntimeController runtime) {
        if (runtime == null || runtime.plan().phase() != null) return runtime;
        try {
            Object job = read(logic, "job");
            if (job == null) return runtime;
            @SuppressWarnings("unchecked")
            Map<IPatternDetails, ?> tasks = (Map<IPatternDetails, ?>) read(job, "tasks");
            var waitingFor = (ListCraftingInventory) read(job, "waitingFor");
            Object cpu = read(logic, "cpu");
            var level = (Level) cpu.getClass().getMethod("getLevel").invoke(cpu);
            return AelisCycleRuntimePreparation.prepare(runtime,
                    AelisCycleRuntimePreparation.taskCounts(tasks), waitingFor.list, level);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            AppliedEnhancements.LOGGER.warn(
                    "Could not inspect saved quantum CPU cycle phase; previous runtime retained", unavailable);
            return runtime;
        }
    }

    /** Repairs scaled pending tasks without restarting an already persisted cycle schedule. */
    public static void reconcile(Object logic, AelisCycleRuntimeController runtime) {
        if (runtime == null) return;
        try {
            Object job = read(logic, "job");
            if (job == null) return;
            @SuppressWarnings("unchecked")
            Map<IPatternDetails, Object> tasks = (Map<IPatternDetails, Object>) read(job, "tasks");
            var counts = new LinkedHashMap<IPatternDetails, Long>();
            for (var entry : tasks.entrySet()) counts.put(entry.getKey(), (Long) read(entry.getValue(), "value"));
            var normalized = AelisCyclePatternNormalization.normalize(counts, runtime.plan());

            var available = new LinkedHashMap<AEKey, Long>();
            normalized.forEach((pattern, count) -> {
                if (count > 0) available.merge(pattern.getDefinition(), count, Math::addExact);
            });
            var required = new LinkedHashMap<AEKey, Long>();
            var state = runtime.snapshot();
            var steps = runtime.plan().steps();
            for (int index = state.stepIndex(); index < steps.size(); index++) {
                var step = steps.get(index);
                long count = index == state.stepIndex() ? state.remainingCrafts() : step.crafts();
                required.merge(step.patternDefinition(), count, Math::addExact);
            }
            for (var entry : required.entrySet()) {
                if (available.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                    AppliedEnhancements.LOGGER.warn(
                            "Saved quantum CPU cycle tasks do not cover the remaining schedule; order retained unchanged");
                    return;
                }
            }
            if (normalized.equals(counts)) return;

            var progressType = tasks.values().iterator().next().getClass();
            var constructor = progressType.getDeclaredConstructor();
            constructor.setAccessible(true);
            var valueField = progressType.getDeclaredField("value");
            valueField.setAccessible(true);
            var replacement = new LinkedHashMap<IPatternDetails, Object>();
            for (var entry : normalized.entrySet()) {
                Object progress = tasks.get(entry.getKey());
                if (progress == null || !entry.getValue().equals(counts.get(entry.getKey()))) {
                    progress = constructor.newInstance();
                    valueField.setLong(progress, entry.getValue());
                }
                replacement.put(entry.getKey(), progress);
            }
            var previous = new LinkedHashMap<>(tasks);
            try {
                tasks.clear();
                tasks.putAll(replacement);
            } catch (RuntimeException failure) {
                tasks.clear();
                tasks.putAll(previous);
                throw failure;
            }
            AppliedEnhancements.LOGGER.info(
                    "Normalized saved quantum CPU cycle tasks: patterns={}, step={}, remainingCrafts={}",
                    replacement.size(), state.stepIndex(), state.remainingCrafts());
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            AppliedEnhancements.LOGGER.warn(
                    "Could not normalize saved quantum CPU cycle tasks; order retained unchanged", unavailable);
        }
    }

    public static AelisCycleRuntimeController recover(Object logic, ListCraftingInventory inventory) {
        try {
            Object job = read(logic, "job");
            if (job == null || !((ListCraftingInventory) read(job, "waitingFor")).list.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<IPatternDetails, Object> tasks = (Map<IPatternDetails, Object>) read(job, "tasks");
            if (tasks.keySet().stream().noneMatch(AelisScaledPattern.class::isInstance)) return null;
            var counts = new LinkedHashMap<IPatternDetails, Long>();
            for (var entry : tasks.entrySet()) counts.put(entry.getKey(), (Long) read(entry.getValue(), "value"));
            var output = (GenericStack) read(job, "finalOutput");
            var recovery = AelisLegacyCycleRecovery.prove(counts, inventory.list,
                    new GenericStack(output.what(), (Long) read(job, "remainingAmount")), Config.CYCLE_SEED_POLICY.get());
            if (recovery == null) {
                AppliedEnhancements.LOGGER.warn("Legacy quantum CPU batch could not be safely restored; order retained unchanged");
                return null;
            }
            var progressType = tasks.values().iterator().next().getClass();
            var constructor = progressType.getDeclaredConstructor();
            constructor.setAccessible(true);
            var valueField = progressType.getDeclaredField("value");
            valueField.setAccessible(true);
            var replacement = new LinkedHashMap<IPatternDetails, Object>();
            for (var entry : recovery.tasks().entrySet()) {
                var progress = constructor.newInstance();
                valueField.setLong(progress, entry.getValue());
                replacement.put(entry.getKey(), progress);
            }
            var runtime = new AelisCycleRuntimeController(recovery.cyclePlan());
            var previous = new LinkedHashMap<>(tasks);
            try {
                tasks.clear();
                tasks.putAll(replacement);
            } catch (RuntimeException failure) {
                tasks.clear();
                tasks.putAll(previous);
                throw failure;
            }
            AppliedEnhancements.LOGGER.info("Restored legacy quantum CPU cycle order: patterns={}, steps={}, seeds={}",
                    replacement.size(), recovery.cyclePlan().steps().size(), recovery.cyclePlan().minimumSeeds());
            return runtime;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            AppliedEnhancements.LOGGER.warn("Could not inspect legacy quantum CPU cycle order; inventory preserved", unavailable);
            return null;
        }
    }

    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
