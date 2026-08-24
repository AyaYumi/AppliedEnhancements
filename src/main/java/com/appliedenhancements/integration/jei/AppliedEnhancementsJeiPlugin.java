package com.appliedenhancements.integration.jei;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.client.menu.OptionalJeiItemContextMenu;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** Optional JEI bootstrap. This class is loaded only by JEI's plugin scanner. */
@JeiPlugin
public final class AppliedEnhancementsJeiPlugin implements IModPlugin {
    private static final ResourceLocation ID =
            AppliedEnhancements.id("network_item_context_menu");
    private JeiItemContextMenuHandler handler;

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        handler = new JeiItemContextMenuHandler(runtime);
        OptionalJeiItemContextMenu.install(handler);
        AppliedEnhancements.LOGGER.info(
                "JEI ingredient context menu integration enabled");
    }

    @Override
    public void onRuntimeUnavailable() {
        if (handler != null) {
            OptionalJeiItemContextMenu.uninstall(handler);
            handler.close();
            handler = null;
        }
    }
}
