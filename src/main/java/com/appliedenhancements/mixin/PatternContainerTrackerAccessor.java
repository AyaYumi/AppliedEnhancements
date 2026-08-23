package com.appliedenhancements.mixin;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternContainer;
import com.appliedenhancements.integration.ae2.PatternContainerTrackerBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "appeng.menu.implementations.PatternAccessTermMenu$ContainerTracker", remap = false)
public abstract class PatternContainerTrackerAccessor
        implements PatternContainerTrackerBridge {
    @Shadow
    @Final
    private PatternContainer container;

    @Shadow
    @Final
    private InternalInventory server;

    @Shadow
    @Final
    private PatternContainerGroup group;

    @Override
    public PatternContainer appliedenhancements$getContainer() {
        return container;
    }

    @Override
    public InternalInventory appliedenhancements$getServerInventory() {
        return server;
    }

    @Override
    public PatternContainerGroup appliedenhancements$getGroup() {
        return group;
    }
}
