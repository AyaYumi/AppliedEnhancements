package com.appliedenhancements.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/** Shared client actions used by AE2 and JEI item menus. */
public final class ClientItemMenuActions {
    private ClientItemMenuActions() {
    }

    /** Opens an editable chat draft instead of sending on a single click. */
    public static void openShareDraft(Component displayName, String id) {
        java.util.Objects.requireNonNull(displayName, "displayName");
        if (id == null || id.isBlank()) {
            return;
        }
        String draft = "[" + displayName.getString() + "] " + id;
        Minecraft.getInstance().setScreen(new ChatScreen(draft));
    }
}
