package com.appliedenhancements.client.menu;

import com.appliedenhancements.AppliedEnhancements;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Configurable GUI trigger for AE2 and JEI item context menus. */
@EventBusSubscriber(
        modid = AppliedEnhancements.MODID,
        value = Dist.CLIENT)
public final class ItemContextMenuKeyMapping {
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.appliedenhancements.open_item_context_menu",
            KeyConflictContext.GUI,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            "key.categories.appliedenhancements");

    private ItemContextMenuKeyMapping() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
    }

    public static boolean matchesMouse(int button) {
        return OPEN_MENU.matchesMouse(button);
    }

    public static boolean matchesKey(
            int keyCode, int scanCode) {
        return OPEN_MENU.matches(keyCode, scanCode);
    }
}
