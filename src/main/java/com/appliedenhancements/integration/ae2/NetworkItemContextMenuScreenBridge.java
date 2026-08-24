package com.appliedenhancements.integration.ae2;

import net.minecraft.client.gui.GuiGraphics;

/** Client screen bridge used by the generic container input hooks. */
public interface NetworkItemContextMenuScreenBridge {
    void appliedenhancements$setTerminalSearch(String searchText);

    boolean appliedenhancements$openNetworkItemMenu(
            double mouseX, double mouseY);

    boolean appliedenhancements$networkItemMenuMouseClicked(
            double mouseX, double mouseY, int button);

    boolean appliedenhancements$networkItemMenuMouseScrolled(
            double mouseX, double mouseY, double deltaX, double deltaY);

    boolean appliedenhancements$networkItemMenuKeyPressed(
            int keyCode, int scanCode, int modifiers);

    boolean appliedenhancements$networkItemMenuCharTyped(
            char codePoint, int modifiers);

    void appliedenhancements$renderNetworkItemMenu(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick);
}
