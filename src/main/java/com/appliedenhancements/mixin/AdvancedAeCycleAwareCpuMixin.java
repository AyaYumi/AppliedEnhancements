package com.appliedenhancements.mixin;

import com.appliedenhancements.api.AelisCycleAwareCpu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU", remap = false)
public abstract class AdvancedAeCycleAwareCpuMixin implements AelisCycleAwareCpu {
}
