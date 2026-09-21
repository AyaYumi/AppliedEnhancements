package com.appliedenhancements.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "appeng.crafting.execution.ExecutingCraftingJob$TaskProgress", remap = false)
public interface ExecutingCraftingTaskProgressAccessor {
    @Accessor("value")
    long appliedenhancements$getValue();
}
