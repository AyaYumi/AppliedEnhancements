package com.appliedenhancements.mixin;

import com.appliedenhancements.integration.ae2.ScreenWidgetBridge;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(Screen.class)
public abstract class ScreenWidgetAccessor implements ScreenWidgetBridge {
    @Accessor("children")
    public abstract List<GuiEventListener> appliedenhancements$children();

    @Accessor("renderables")
    public abstract List<Renderable> appliedenhancements$renderables();

    @Accessor("narratables")
    public abstract List<NarratableEntry> appliedenhancements$narratables();

    @Override
    public <T extends GuiEventListener & Renderable & NarratableEntry>
            T appliedenhancements$addRenderableWidget(T widget) {
        appliedenhancements$renderables().add(widget);
        appliedenhancements$children().add(widget);
        appliedenhancements$narratables().add(widget);
        return widget;
    }

    @Override
    @Invoker("removeWidget")
    public abstract void appliedenhancements$removeWidget(GuiEventListener widget);
}
