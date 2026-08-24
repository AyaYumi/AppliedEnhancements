package com.appliedenhancements.client.pattern;

import com.appliedenhancements.client.menu.ClientContextMenuState;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Small AE-colored secondary menu used for cut/paste actions. */
public final class PatternQuickMoveContextMenu {
    private static final int WIDTH = 68;
    private static final int ROW_HEIGHT = 14;

    private int x;
    private int y;
    private List<Entry> entries = List.of();

    public void open(int mouseX, int mouseY, int screenWidth, int screenHeight, List<Entry> entries) {
        close();
        this.entries = List.copyOf(entries);
        if (!this.entries.isEmpty()) {
            ClientContextMenuState.setOpen(this, true);
        }
        int height = this.entries.size() * ROW_HEIGHT + 4;
        this.x = Math.max(2, Math.min(mouseX, screenWidth - WIDTH - 2));
        this.y = Math.max(2, Math.min(mouseY, screenHeight - height - 2));
    }

    public boolean isOpen() {
        return !entries.isEmpty();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isOpen()) {
            return false;
        }
        if (button == 0 && mouseX >= x && mouseX < x + WIDTH) {
            int row = ((int) mouseY - y - 2) / ROW_HEIGHT;
            if (row >= 0 && row < entries.size()
                    && mouseY >= y + 2 && mouseY < y + 2 + entries.size() * ROW_HEIGHT) {
                Entry selected = entries.get(row);
                close();
                selected.action.run();
                return true;
            }
        }
        close();
        return true;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isOpen()) {
            return;
        }
        int height = entries.size() * ROW_HEIGHT + 4;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        try {
            graphics.fill(x, y, x + WIDTH, y + height, 0xFFAEB2C4);
            graphics.fill(x, y, x + WIDTH, y + 1, 0xFFF4F6FF);
            graphics.fill(x, y + height - 1, x + WIDTH, y + height, 0xFF4D5060);
            graphics.fill(x, y, x + 1, y + height, 0xFFF4F6FF);
            graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + height, 0xFF4D5060);

            var font = Minecraft.getInstance().font;
            for (int row = 0; row < entries.size(); row++) {
                int rowY = y + 2 + row * ROW_HEIGHT;
                if (mouseX >= x + 1 && mouseX < x + WIDTH - 1
                        && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    graphics.fill(x + 1, rowY, x + WIDTH - 1, rowY + ROW_HEIGHT, 0x8055AAFF);
                }
                int textY = rowY + Math.max(1, (ROW_HEIGHT - font.lineHeight) / 2);
                graphics.drawString(font, entries.get(row).label, x + 7, textY,
                        0xFF303342, false);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    public void close() {
        ClientContextMenuState.setOpen(this, false);
        entries = List.of();
    }

    public record Entry(Component label, Runnable action) {
    }
}
