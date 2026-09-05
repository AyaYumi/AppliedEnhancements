package com.appliedenhancements.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.AbstractTableRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AbstractTableRenderer.class, remap = false)
public interface AbstractTableRendererAccessor {
    @Accessor("screen")
    AEBaseScreen<?> appliedenhancements$getScreen();
}
