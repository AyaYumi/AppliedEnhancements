package com.appliedenhancements.mixin;

import com.appliedenhancements.integration.ae2.ScreenWidgetBridge;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Screen.class)
public interface ScreenWidgetAccessor extends ScreenWidgetBridge {
    @Override
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry>
            T appliedenhancements$addRenderableWidget(T widget);

    @Override
    @Invoker("removeWidget")
    void appliedenhancements$removeWidget(GuiEventListener widget);
}
