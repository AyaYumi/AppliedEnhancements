package com.appliedenhancements.mixin;

import appeng.client.gui.AEBaseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** AE2 15 exposes same-menu screen switching as protected, unlike AE2 19. */
@Mixin(value = AEBaseScreen.class, remap = false)
public interface AEBaseScreenAccessor {
    @Invoker("switchToScreen")
    void appliedenhancements$switchToScreen(AEBaseScreen<?> screen);
}
