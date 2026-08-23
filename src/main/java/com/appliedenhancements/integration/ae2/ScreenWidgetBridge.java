package com.appliedenhancements.integration.ae2;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/** Internal bridge for adding an interactive widget to an initialized screen. */
public interface ScreenWidgetBridge {
    <T extends GuiEventListener & Renderable & NarratableEntry>
            T appliedenhancements$addRenderableWidget(T widget);

    void appliedenhancements$removeWidget(GuiEventListener widget);
}
