package com.appliedenhancements.mixin;

import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChildCraftingSimulationState.class, remap = false)
public interface AelisChildSimulationStateAccessor {
    @Accessor("parent")
    ICraftingInventory appliedenhancements$getParent();
}
