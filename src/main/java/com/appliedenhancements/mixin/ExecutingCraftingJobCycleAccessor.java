package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public interface ExecutingCraftingJobCycleAccessor {
    @Accessor("tasks")
    Map<IPatternDetails, ?> appliedenhancements$getTasks();

    @Accessor("waitingFor")
    ListCraftingInventory appliedenhancements$getWaitingFor();

    @Accessor("finalOutput")
    GenericStack appliedenhancements$getFinalOutput();

    @Accessor("remainingAmount")
    long appliedenhancements$getRemainingAmount();

    @Accessor("timeTracker")
    ElapsedTimeTracker appliedenhancements$getTimeTracker();
}
