package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rebuilds AE2's progress projection without allowing pattern-output products to wrap. */
@Mixin(value = ExecutingCraftingJob.class, remap = false)
abstract class ExecutingCraftingJobLongSafetyMixin {
    @Shadow
    @Final
    private ListCraftingInventory waitingFor;

    @Shadow
    @Final
    private Map<IPatternDetails, ?> tasks;

    @Shadow
    @Final
    private ElapsedTimeTracker timeTracker;

    @Inject(
            method = "<init>(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/crafting/execution/ExecutingCraftingJob$CraftingDifferenceListener;Lappeng/crafting/CraftingLink;Ljava/lang/Integer;)V",
            at = @At("RETURN"))
    private void appliedenhancements$rebuildSaturatedProgress(CallbackInfo callback) {
        var exactByType = new IdentityHashMap<AEKeyType, BigInteger>();
        for (var entry : waitingFor.list) {
            merge(exactByType, entry.getKey().getType(),
                    BigInteger.valueOf(entry.getLongValue())
                            .multiply(BigInteger.valueOf(
                                    entry.getKey().getAmountPerUnit())));
        }
        for (var task : tasks.entrySet()) {
            long count = ((ExecutingCraftingTaskProgressAccessor) task.getValue())
                    .appliedenhancements$getValue();
            if (count <= 0) {
                continue;
            }
            for (var output : task.getKey().getOutputs()) {
                merge(exactByType, output.what().getType(),
                        BigInteger.valueOf(output.amount())
                                .multiply(BigInteger.valueOf(count))
                                .multiply(BigInteger.valueOf(
                                        output.what().getAmountPerUnit())));
            }
        }

        var access = (ElapsedTimeTrackerAccessor) timeTracker;
        Reference2LongMap<AEKeyType> started = access.appliedenhancements$getStartedWorkByType();
        Reference2LongMap<AEKeyType> completed = access.appliedenhancements$getCompletedWorkByType();
        started.clear();
        completed.clear();
        exactByType.forEach((type, amount) -> started.put(
                type,
                amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0
                        ? Long.MAX_VALUE
                        : amount.longValueExact()));
    }

    private static void merge(
            Map<AEKeyType, BigInteger> target, AEKeyType type, BigInteger amount) {
        if (amount.signum() > 0) {
            target.merge(type, amount, BigInteger::add);
        }
    }
}
