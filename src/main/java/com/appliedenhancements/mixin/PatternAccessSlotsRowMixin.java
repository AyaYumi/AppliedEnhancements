package com.appliedenhancements.mixin;

import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import com.appliedenhancements.integration.ae2.PatternTerminalSlotsRowBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "appeng.client.gui.me.patternaccess.PatternAccessTermScreen$SlotsRow", remap = false)
public abstract class PatternAccessSlotsRowMixin implements PatternTerminalSlotsRowBridge {
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
