package com.appliedenhancements.mixin;

import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.WidgetContainer;
import java.util.Map;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Used only by our independent confirmation view, never by the original screen. */
@Mixin(value = WidgetContainer.class, remap = false)
public interface WidgetContainerAccessor {
    @Accessor("widgets")
    Map<String, AbstractWidget> appliedenhancements$getWidgets();

    @Accessor("compositeWidgets")
    Map<String, ICompositeWidget> appliedenhancements$getCompositeWidgets();
}
