package com.appliedenhancements.mixin;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import com.appliedenhancements.integration.ae2.PatternTerminalGroupHeaderBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal$GroupHeaderRow", remap = false)
public abstract class ExtendedPatternAccessGroupHeaderMixin
        implements PatternTerminalGroupHeaderBridge {
    @Shadow
    @Final
    private PatternContainerGroup group;

    @Override
    public PatternContainerGroup appliedenhancements$getGroup() {
        return group;
    }
}
