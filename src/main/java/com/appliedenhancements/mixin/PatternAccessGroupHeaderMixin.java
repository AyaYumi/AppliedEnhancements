package com.appliedenhancements.mixin;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import com.appliedenhancements.integration.ae2.PatternTerminalGroupHeaderBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "appeng.client.gui.me.patternaccess.PatternAccessTermScreen$GroupHeaderRow", remap = false)
public abstract class PatternAccessGroupHeaderMixin
        implements PatternTerminalGroupHeaderBridge {
    @Shadow
    @Final
    private PatternContainerGroup group;

    @Override
    public PatternContainerGroup appliedenhancements$getGroup() {
        return group;
    }
}
