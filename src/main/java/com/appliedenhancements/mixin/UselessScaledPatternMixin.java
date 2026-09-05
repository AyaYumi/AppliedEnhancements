package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import com.github.appliedenhancements.integration.ae2.AelisScaledPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.ScaledProcessingPattern",
        remap = false)
public abstract class UselessScaledPatternMixin implements AelisScaledPattern {
    @Shadow public abstract IPatternDetails getOriginal();
    @Shadow public abstract long getOperationsPerPush();

    @Override public IPatternDetails appliedenhancements$originalPattern() { return getOriginal(); }
    @Override public long appliedenhancements$operationsPerPush() { return getOperationsPerPush(); }
}
