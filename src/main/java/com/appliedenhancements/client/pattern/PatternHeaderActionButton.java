package com.appliedenhancements.client.pattern;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.client.gui.Icon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/** Rendering and hit testing for per-machine cut/paste header actions. */
public final class PatternHeaderActionButton {
    public static final int SIZE = 10;
    private static final int NAME_X = 20;
    private static final int MAX_ACTION_X = 142;

    private PatternHeaderActionButton() {
    }

    public static int actionStartX(PatternContainerGroup group, int groupSize) {
        var font = Minecraft.getInstance().font;
        var text = Language.getInstance().getVisualOrder(
                font.substrByWidth(
                        displayName(group, groupSize),
                        MAX_ACTION_X - NAME_X - 3));
        return Math.min(MAX_ACTION_X, NAME_X + font.width(text) + 3);
    }

    private static FormattedText displayName(
            PatternContainerGroup group, int groupSize) {
        return groupSize > 1
                ? Component.empty().append(group.name())
                        .append(Component.literal(" (" + groupSize + ')'))
                : group.name();
    }

    public static void render(
            GuiGraphics graphics,
            int x,
            int y,
            Action action,
            boolean hovered,
            boolean active) {
        Icon background = hovered
                ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                : Icon.TOOLBAR_BUTTON_BACKGROUND;
        background.getBlitter().dest(x, y, SIZE, SIZE).zOffset(22).blit(graphics);

        var font = Minecraft.getInstance().font;
        Component label = Component.translatable(action.translationKey);
        int textX = x + (SIZE - font.width(label)) / 2;
        int textY = y + 1;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 24);
        try {
            graphics.drawString(
                    font,
                    label,
                    textX,
                    textY,
                    active ? 0xFFE0E0E0 : 0xFF777777,
                    false);
        } finally {
            graphics.pose().popPose();
        }
    }

    public enum Action {
        CUT("gui.appliedenhancements.pattern_quick_move.cut_short"),
        PASTE("gui.appliedenhancements.pattern_quick_move.paste_short");

        private final String translationKey;

        Action(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    public record Hit(
            int x,
            int y,
            Action action,
            PatternContainerGroup group) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + SIZE
                    && mouseY >= y && mouseY < y + SIZE;
        }
    }
}
