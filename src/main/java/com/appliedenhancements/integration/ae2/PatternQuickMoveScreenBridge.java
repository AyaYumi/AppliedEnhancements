package com.appliedenhancements.integration.ae2;

import net.minecraft.client.gui.GuiGraphics;

public interface PatternQuickMoveScreenBridge {
    boolean appliedenhancements$quickMoveMouseClicked(double mouseX, double mouseY, int button);

    boolean appliedenhancements$quickMoveMouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY);

    boolean appliedenhancements$quickMoveMouseReleased(double mouseX, double mouseY, int button);

    void appliedenhancements$renderQuickMoveOverlay(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick);
}
