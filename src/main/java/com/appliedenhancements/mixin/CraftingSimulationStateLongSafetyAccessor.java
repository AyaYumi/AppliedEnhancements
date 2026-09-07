package com.appliedenhancements.mixin;

import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;
import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Internal field view used only to validate CraftingSimulationState.applyDiff. */
@ApiStatus.Internal
@Mixin(value = CraftingSimulationState.class, remap = false)
public interface CraftingSimulationStateLongSafetyAccessor {
    @Accessor("unmodifiedCache")
    KeyCounter appliedenhancements$getUnmodifiedCache();

    @Accessor("modifiableCache")
    KeyCounter appliedenhancements$getModifiableCache();

    @Accessor("requiredExtract")
    KeyCounter appliedenhancements$getRequiredExtract();

    @Accessor("crafts")
    Map<IPatternDetails, Long> appliedenhancements$getCrafts();
}
