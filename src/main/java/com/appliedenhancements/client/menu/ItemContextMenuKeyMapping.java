package com.appliedenhancements.client.menu;

import com.appliedenhancements.AppliedEnhancements;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Independent GUI triggers for pattern Quick Move and AE2/JEI item actions. */
@EventBusSubscriber(
        modid = AppliedEnhancements.MODID,
        value = Dist.CLIENT)
public final class ItemContextMenuKeyMapping {
    // Retain the former shared ID for Quick Move so existing right-click/custom
    // bindings survive, while item actions receive their new Alt+right default.
    public static final KeyMapping OPEN_PATTERN_MENU = new KeyMapping(
            "key.appliedenhancements.open_item_context_menu",
            KeyConflictContext.GUI,
            KeyModifier.NONE,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            "key.categories.appliedenhancements");

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.appliedenhancements.open_item_actions_menu",
            KeyConflictContext.GUI,
            KeyModifier.ALT,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            "key.categories.appliedenhancements");

    private ItemContextMenuKeyMapping() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PATTERN_MENU);
        event.register(OPEN_MENU);
    }

    public static boolean matchesMouse(int button) {
        return OPEN_MENU.isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(button));
    }

    public static boolean matchesKey(
            int keyCode, int scanCode) {
        return OPEN_MENU.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }

    public static boolean matchesPatternMouse(int button) {
        return OPEN_PATTERN_MENU.isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(button));
    }

    public static boolean matchesPatternKey(int keyCode, int scanCode) {
        return OPEN_PATTERN_MENU.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }
}
