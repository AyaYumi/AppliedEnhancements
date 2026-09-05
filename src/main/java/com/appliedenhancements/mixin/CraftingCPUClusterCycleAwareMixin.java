package com.appliedenhancements.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.appliedenhancements.api.AelisCycleAwareCpu;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterCycleAwareMixin
        implements AelisCycleAwareCpu {
}
