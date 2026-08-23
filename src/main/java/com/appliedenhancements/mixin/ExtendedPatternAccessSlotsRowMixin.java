package com.appliedenhancements.mixin;

import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import com.appliedenhancements.integration.ae2.PatternTerminalSlotsRowBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal$SlotsRow", remap = false)
public abstract class ExtendedPatternAccessSlotsRowMixin
        implements PatternTerminalSlotsRowBridge {
    @Shadow
    @Final
    private PatternContainerRecord container;

    @Shadow
    @Final
    private int offset;

    @Shadow
    @Final
    private int slots;

    @Override
    public PatternContainerRecord appliedenhancements$getContainer() {
        return container;
    }

    @Override
    public int appliedenhancements$getOffset() {
        return offset;
    }

    @Override
    public int appliedenhancements$getSlotCount() {
        return slots;
    }
}
