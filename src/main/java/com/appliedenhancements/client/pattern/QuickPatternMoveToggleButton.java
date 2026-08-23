package com.appliedenhancements.client.pattern;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

/** AE2-style text toggle for client-side pattern selection mode. */
public final class QuickPatternMoveToggleButton extends IconButton {
    private static final int BUTTON_WIDTH = 22;
    private static final int BUTTON_HEIGHT = 12;

    private final Consumer<Boolean> listener;
    private boolean selected;
    private int selectedCount;
    private int cutCount;

    public QuickPatternMoveToggleButton(Consumer<Boolean> listener) {
        super(null);
        this.listener = listener;
        this.width = BUTTON_WIDTH;
        this.height = BUTTON_HEIGHT;
    }

    @Override
    public void onPress() {
        selected = !selected;
        listener.accept(selected);
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setCounts(int selectedCount, int cutCount) {
        this.selectedCount = selectedCount;
        this.cutCount = cutCount;
    }

    @Override
    protected Icon getIcon() {
        return null;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        int yOffset = isHovered() ? 1 : 0;
        Icon background = isHovered()
                ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                : isFocused()
                        ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS
                        : Icon.TOOLBAR_BUTTON_BACKGROUND;
        background.getBlitter()
                .dest(getX(), getY() + yOffset, BUTTON_WIDTH, BUTTON_HEIGHT)
                .zOffset(2)
                .blit(graphics);

        var font = Minecraft.getInstance().font;
        Component label = Component.translatable(
                "gui.appliedenhancements.pattern_quick_move.button");
        int labelWidth = font.width(label);
        float textScale = Math.min(1.0F, (BUTTON_WIDTH - 4.0F) / labelWidth);
        int textX = Math.round(
                (getX() + BUTTON_WIDTH / 2.0F) / textScale - labelWidth / 2.0F);
        int textY = Math.round((getY() + 2 + yOffset) / textScale);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 4);
        graphics.pose().scale(textScale, textScale, 1.0F);
        try {
            graphics.drawString(
                    font,
                    label,
                    textX,
                    textY,
                    selected ? 0xFF55FFFF : 0xFFE0E0E0,
                    false);
        } finally {
            graphics.pose().popPose();
        }
    }

    @Override
    public Rect2i getTooltipArea() {
        return new Rect2i(getX(), getY(), BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    @Override
    public List<Component> getTooltipMessage() {
        var result = new ArrayList<Component>();
        result.add(Component.translatable(
                "gui.appliedenhancements.pattern_quick_move"));
        result.add(Component.translatable(selected
                ? "gui.appliedenhancements.pattern_quick_move.enabled"
                : "gui.appliedenhancements.pattern_quick_move.disabled"));
        if (selectedCount > 0) {
            result.add(Component.translatable(
                    "gui.appliedenhancements.pattern_quick_move.selected",
                    selectedCount));
        }
        if (cutCount > 0) {
            result.add(Component.translatable(
                    "gui.appliedenhancements.pattern_quick_move.cut_count",
                    cutCount));
        }
        return List.copyOf(result);
    }
}
