package com.appliedenhancements.runtime;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class AelisCycleExecutionNbt {
    private static final int VERSION = 2;

    private AelisCycleExecutionNbt() {
    }

    public static CompoundTag write(
            AelisCycleRuntimeController runtime,
            HolderLookup.Provider registries) {
        var root = new CompoundTag();
        root.putInt("version", VERSION);
        var state = runtime.snapshot();
        root.putInt("stepIndex", state.stepIndex());
        root.putLong("remainingCrafts", state.remainingCrafts());
        root.put("pendingOutputs", writeAmounts(state.pendingOutputs(), registries));
        root.putString("seedPolicy", runtime.plan().seedPolicy().name());

        AelisCycleExecutionPlan plan = runtime.plan();
        var steps = new ListTag();
        for (AelisCycleExecutionPlan.Step step : plan.steps()) {
            var tag = new CompoundTag();
            tag.put("pattern", step.patternDefinition().toTagGeneric(registries));
            tag.putLong("crafts", step.crafts());
            tag.put("inputs", writeAmounts(step.inputsPerCraft(), registries));
            tag.put("self", writeKeys(step.selfReplenishingInputs(), registries));
            steps.add(tag);
        }
        root.put("steps", steps);
        root.put("minimumSeeds", writeAmounts(plan.minimumSeeds(), registries));
        root.put("protectedKeys", writeKeys(plan.protectedKeys(), registries));
        if (plan.phase() != null) {
            var phase = new CompoundTag();
            phase.put("prerequisitePatterns", writeKeys(plan.phase().prerequisitePatterns(), registries));
            var outputs = new ListTag();
            for (var entry : plan.phase().outputsPerPattern().entrySet()) {
                var pattern = new CompoundTag();
                pattern.put("pattern", entry.getKey().toTagGeneric(registries));
                pattern.put("outputs", writeAmounts(entry.getValue(), registries));
                outputs.add(pattern);
            }
            phase.put("outputsPerPattern", outputs);
            root.put("phase", phase);
        }
        return root;
    }

    public static AelisCycleRuntimeController read(
            CompoundTag root,
            HolderLookup.Provider registries) {
        if (root == null || root.getInt("version") < 1 || root.getInt("version") > VERSION) {
            return null;
        }
        try {
            var steps = new ArrayList<AelisCycleExecutionPlan.Step>();
            ListTag stepTags = root.getList("steps", Tag.TAG_COMPOUND);
            for (int index = 0; index < stepTags.size(); index++) {
                CompoundTag tag = stepTags.getCompound(index);
                AEKey pattern = AEKey.fromTagGeneric(
                        registries, tag.getCompound("pattern"));
                if (pattern == null) {
                    return null;
                }
                steps.add(new AelisCycleExecutionPlan.Step(
                        pattern,
                        tag.getLong("crafts"),
                        readAmounts(tag.getList("inputs", Tag.TAG_COMPOUND), registries),
                        readKeys(tag.getList("self", Tag.TAG_COMPOUND), registries)));
            }
            AelisCycleSeedPolicy seedPolicy = root.contains("seedPolicy", Tag.TAG_STRING)
                    ? AelisCycleSeedPolicy.valueOf(root.getString("seedPolicy"))
                    : AelisCycleSeedPolicy.MAX_THROUGHPUT;
            AelisCycleExecutionPlan.Phase phase = null;
            if (root.getInt("version") >= 2 && root.contains("phase", Tag.TAG_COMPOUND)) {
                var phaseTag = root.getCompound("phase");
                var outputs = new LinkedHashMap<AEKey, Map<AEKey, Long>>();
                var patternTags = phaseTag.getList("outputsPerPattern", Tag.TAG_COMPOUND);
                for (int index = 0; index < patternTags.size(); index++) {
                    var patternTag = patternTags.getCompound(index);
                    var pattern = AEKey.fromTagGeneric(registries, patternTag.getCompound("pattern"));
                    var amounts = readAmounts(patternTag.getList("outputs", Tag.TAG_COMPOUND), registries);
                    if (pattern == null || outputs.putIfAbsent(pattern, amounts) != null) {
                        throw new IllegalArgumentException("Invalid cycle phase output entry");
                    }
                }
                phase = new AelisCycleExecutionPlan.Phase(
                        readKeys(phaseTag.getList("prerequisitePatterns", Tag.TAG_COMPOUND), registries),
                        outputs);
            }
            var plan = new AelisCycleExecutionPlan(
                    steps,
                    readAmounts(
                            root.getList("minimumSeeds", Tag.TAG_COMPOUND), registries),
                    readKeys(
                            root.getList("protectedKeys", Tag.TAG_COMPOUND), registries),
                    seedPolicy,
                    phase);
            return AelisCycleRuntimeController.withCyclePhase(
                    plan,
                    new AelisCycleRuntimeController.State(
                            root.getInt("stepIndex"),
                            root.getLong("remainingCrafts"),
                            root.getInt("version") >= 2
                                    ? readAmounts(root.getList("pendingOutputs", Tag.TAG_COMPOUND), registries)
                                    : Map.of()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static ListTag writeAmounts(
            Map<AEKey, Long> values,
            HolderLookup.Provider registries) {
        var result = new ListTag();
        for (var entry : values.entrySet()) {
            var tag = new CompoundTag();
            tag.put("key", entry.getKey().toTagGeneric(registries));
            tag.putLong("amount", entry.getValue());
            result.add(tag);
        }
        return result;
    }

    private static Map<AEKey, Long> readAmounts(
            ListTag values,
            HolderLookup.Provider registries) {
        var result = new LinkedHashMap<AEKey, Long>();
        for (int index = 0; index < values.size(); index++) {
            CompoundTag tag = values.getCompound(index);
            AEKey key = AEKey.fromTagGeneric(registries, tag.getCompound("key"));
            long amount = tag.getLong("amount");
            if (key == null || amount <= 0 || result.putIfAbsent(key, amount) != null) {
                throw new IllegalArgumentException("Invalid cycle amount entry");
            }
        }
        return Map.copyOf(result);
    }

    private static ListTag writeKeys(
            Iterable<AEKey> values,
            HolderLookup.Provider registries) {
        var result = new ListTag();
        for (AEKey value : values) {
            var tag = new CompoundTag();
            tag.put("key", value.toTagGeneric(registries));
            result.add(tag);
        }
        return result;
    }

    private static java.util.Set<AEKey> readKeys(
            ListTag values,
            HolderLookup.Provider registries) {
        var result = new LinkedHashSet<AEKey>();
        for (int index = 0; index < values.size(); index++) {
            AEKey key = AEKey.fromTagGeneric(
                    registries, values.getCompound(index).getCompound("key"));
            if (key == null || !result.add(key)) {
                throw new IllegalArgumentException("Invalid cycle key entry");
            }
        }
        return java.util.Set.copyOf(result);
    }
}
