package com.appliedenhancements.integration.jei;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.client.menu.OptionalJeiItemContextMenu;
import com.appliedenhancements.client.plan.OptionalPlanBookmarks;
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
    private JeiPlanBookmarks bookmarks;

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        handler = new JeiItemContextMenuHandler(runtime);
        OptionalJeiItemContextMenu.install(handler);
        try {
            bookmarks = new JeiPlanBookmarks(runtime);
            OptionalPlanBookmarks.install(bookmarks);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            com.appliedenhancements.runtime.AelisPlanningLog.warn("JEI bookmarks are unavailable", failure);
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        if (bookmarks != null) {
            OptionalPlanBookmarks.uninstall(bookmarks);
            bookmarks = null;
        }
        if (handler != null) {
            OptionalJeiItemContextMenu.uninstall(handler);
            handler.close();
            handler = null;
        }
    }
}
