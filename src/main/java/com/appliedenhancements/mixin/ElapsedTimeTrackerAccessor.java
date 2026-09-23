package com.appliedenhancements.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ElapsedTimeTracker.class, remap = false)
public interface ElapsedTimeTrackerAccessor {
    @Accessor("startedWorkByType")
    Reference2LongMap<AEKeyType> appliedenhancements$getStartedWorkByType();

    @Accessor("completedWorkByType")
    Reference2LongMap<AEKeyType> appliedenhancements$getCompletedWorkByType();

    @Invoker("decrementItems")
    void appliedenhancements$decrementItems(long amount, AEKeyType keyType);
}
