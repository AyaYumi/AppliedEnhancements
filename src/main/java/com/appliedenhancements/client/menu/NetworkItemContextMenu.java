package com.appliedenhancements.client.menu;

import appeng.api.stacks.AEItemKey;
import com.appliedenhancements.ae2.ExactLongValueParser;
import com.appliedenhancements.api.NetworkItemContextMenuApi;
import com.appliedenhancements.api.NetworkItemContextMenuApi.Context;
import com.appliedenhancements.api.NetworkItemContextMenuApi.Entry;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** AE-colored context menu for one ME terminal repository entry. */
public final class NetworkItemContextMenu {
    private static final int MIN_WIDTH = 112;
    private static final int MAX_WIDTH = 220;
    private static final int ROW_HEIGHT = 14;
    private static final int AMOUNT_HEIGHT = 62;

    private int x;
    private int y;
    private int width;
    private List<ActionEntry> entries = List.of();
    private EditBox amountField;
    private long maximumAmount;
    private LongConsumer amountConsumer;

    public void open(
            int mouseX,
            int mouseY,
            int screenWidth,
            int screenHeight,
            Context context) {
        java.util.Objects.requireNonNull(context, "context");
        this.amountField = null;
        this.amountConsumer = null;

        var built = new ArrayList<ActionEntry>();
        if (context.key() instanceof AEItemKey && context.storedAmount() > 0) {
            built.add(new ActionEntry(Component.translatable(
                    "gui.appliedenhancements.network_item_menu.extract_one"),
                    context::extractOne));
            built.add(new ActionEntry(Component.translatable(
                    "gui.appliedenhancements.network_item_menu.extract_stack"),
                    context::extractStack));
            built.add(new ActionEntry(Component.translatable(
                    "gui.appliedenhancements.network_item_menu.extract_amount"),
                    () -> openAmountInput(
                            screenWidth, screenHeight,
                            context.storedAmount(), context::extractAmount)));
        }
        if (context.craftable()) {
            built.add(new ActionEntry(Component.translatable(
                    "gui.appliedenhancements.network_item_menu.craft"),
                    context::requestCraft));
        }
        built.add(new ActionEntry(Component.translatable(
                "gui.appliedenhancements.jei_item_menu.copy_name"),
                context::copyName));
        built.add(new ActionEntry(Component.translatable(
                "gui.appliedenhancements.network_item_menu.copy_id"),
                context::copyId));
        if (context.key().getModId() != null) {
            built.add(new ActionEntry(Component.translatable(
                    "gui.appliedenhancements.item_menu.search_same_mod"),
                    context::searchSameMod));
        }
        for (Entry entry : NetworkItemContextMenuApi.entries(context)) {
            built.add(new ActionEntry(
                    entry.label(), () -> entry.activate(context)));
        }
        openActions(mouseX, mouseY, screenWidth, screenHeight, built);
    }

    /** Opens the same menu renderer for non-AE2 client integrations. */
    public void openActions(
            int mouseX,
            int mouseY,
            int screenWidth,
            int screenHeight,
            List<ActionEntry> entries) {
        close();
        this.entries = List.copyOf(entries);
        if (this.entries.isEmpty()) {
            return;
        }
        ClientContextMenuState.setOpen(this, true);

        var font = Minecraft.getInstance().font;
        int widest = this.entries.stream()
                .mapToInt(entry -> font.width(entry.label()))
                .max()
                .orElse(MIN_WIDTH - 14);
        this.width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widest + 14));
        int height = this.entries.size() * ROW_HEIGHT + 4;
        this.x = clamp(mouseX, 2, Math.max(2, screenWidth - width - 2));
        this.y = clamp(mouseY, 2, Math.max(2, screenHeight - height - 2));
    }

    public boolean isOpen() {
        return !entries.isEmpty() || amountField != null;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isOpen()) {
            return false;
        }
        if (amountField != null) {
            return amountMouseClicked(mouseX, mouseY, button);
        }

        if (button == 0 && mouseX >= x && mouseX < x + width) {
            int row = ((int) mouseY - y - 2) / ROW_HEIGHT;
            if (row >= 0 && row < entries.size()
                    && mouseY >= y + 2
                    && mouseY < y + 2 + entries.size() * ROW_HEIGHT) {
                ActionEntry selected = entries.get(row);
                entries = List.of();
                selected.activate();
                if (entries.isEmpty() && amountField == null) {
                    close();
                }
                return true;
            }
        }
        close();
        return true;
    }

    public boolean mouseScrolled(
            double mouseX, double mouseY, double deltaX, double deltaY) {
        return isOpen();
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isOpen()) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (amountField != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER
                    || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmAmount();
                return true;
            }
            amountField.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return true;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (amountField == null) {
            return isOpen();
        }
        amountField.charTyped(codePoint, modifiers);
        return true;
    }

    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!isOpen()) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 700);
        try {
            if (amountField != null) {
                renderAmountInput(graphics, mouseX, mouseY, partialTick);
            } else {
                renderEntries(graphics, mouseX, mouseY);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    public void close() {
        ClientContextMenuState.setOpen(this, false);
        entries = List.of();
        amountField = null;
        amountConsumer = null;
        maximumAmount = 0;
    }

    private void openAmountInput(
            int screenWidth,
            int screenHeight,
            long maximumAmount,
            LongConsumer amountConsumer) {
        this.entries = List.of();
        this.maximumAmount = Math.max(1, maximumAmount);
        this.amountConsumer = java.util.Objects.requireNonNull(
                amountConsumer, "amountConsumer");
        this.width = Math.max(142, width);
        this.x = clamp(x, 2, Math.max(2, screenWidth - width - 2));
        this.y = clamp(y, 2, Math.max(2, screenHeight - AMOUNT_HEIGHT - 2));
        this.amountField = new EditBox(
                Minecraft.getInstance().font,
                x + 6,
                y + 20,
                width - 12,
                16,
                Component.translatable(
                        "gui.appliedenhancements.network_item_menu.amount_prompt"));
        amountField.setMaxLength(20);
        amountField.setValue(Long.toString(Math.min(this.maximumAmount, 64)));
        amountField.setFocused(true);
        amountField.setCanLoseFocus(false);
    }

    private boolean amountMouseClicked(
            double mouseX, double mouseY, int button) {
        if (button == 0 && contains(mouseX, mouseY, x + 6, y + 41, 60, 16)) {
            confirmAmount();
            return true;
        }
        if (button == 0
                && contains(mouseX, mouseY, x + width - 66, y + 41, 60, 16)) {
            close();
            return true;
        }
        if (contains(mouseX, mouseY, x, y, width, AMOUNT_HEIGHT)) {
            amountField.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        close();
        return true;
    }

    private void renderEntries(
            GuiGraphics graphics, int mouseX, int mouseY) {
        int height = entries.size() * ROW_HEIGHT + 4;
        renderPanel(graphics, x, y, width, height);
        var font = Minecraft.getInstance().font;
        for (int row = 0; row < entries.size(); row++) {
            int rowY = y + 2 + row * ROW_HEIGHT;
            if (contains(mouseX, mouseY, x + 1, rowY, width - 2, ROW_HEIGHT)) {
                graphics.fill(x + 1, rowY, x + width - 1,
                        rowY + ROW_HEIGHT, 0x8055AAFF);
            }
            int textY = rowY + Math.max(1, (ROW_HEIGHT - font.lineHeight) / 2);
            graphics.drawString(font, entries.get(row).label(),
                    x + 7, textY, 0xFF303342, false);
        }
    }

    private void renderAmountInput(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderPanel(graphics, x, y, width, AMOUNT_HEIGHT);
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, Component.translatable(
                        "gui.appliedenhancements.network_item_menu.amount_prompt"),
                x + 6, y + 7, 0xFF303342, false);

        boolean valid = parseAmount().isPresent();
        amountField.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF5555);
        amountField.render(graphics, mouseX, mouseY, partialTick);
        renderSmallButton(graphics, x + 6, y + 41, 60, 16,
                Component.translatable("gui.appliedenhancements.network_item_menu.confirm"),
                valid, contains(mouseX, mouseY, x + 6, y + 41, 60, 16));
        renderSmallButton(graphics, x + width - 66, y + 41, 60, 16,
                Component.translatable("gui.appliedenhancements.network_item_menu.cancel"),
                true,
                contains(mouseX, mouseY, x + width - 66, y + 41, 60, 16));
    }

    private void confirmAmount() {
        OptionalLong parsed = parseAmount();
        if (parsed.isEmpty()) {
            return;
        }
        LongConsumer consumer = amountConsumer;
        long amount = parsed.getAsLong();
        close();
        consumer.accept(amount);
    }

    private OptionalLong parseAmount() {
        if (amountField == null) {
            return OptionalLong.empty();
        }
        var format = new DecimalFormat(
                "#.######", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setParseBigDecimal(true);
        return ExactLongValueParser.parse(
                amountField.getValue(), format, 1, 1, maximumAmount);
    }

    private static void renderPanel(
            GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFFAEB2C4);
        graphics.fill(x, y, x + width, y + 1, 0xFFF4F6FF);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF4D5060);
        graphics.fill(x, y, x + 1, y + height, 0xFFF4F6FF);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF4D5060);
    }

    private static void renderSmallButton(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            Component label,
            boolean active,
            boolean hovered) {
        int color = !active ? 0xFF777985
                : hovered ? 0xFF86B8E8 : 0xFF9297AB;
        graphics.fill(x, y, x + width, y + height, color);
        graphics.fill(x, y, x + width, y + 1, 0xFFDDE4F8);
        var font = Minecraft.getInstance().font;
        int textX = x + (width - font.width(label)) / 2;
        graphics.drawString(font, label, textX, y + 4,
                active ? 0xFFFFFFFF : 0xFFB0B0B0, false);
    }

    private static boolean contains(
            double mouseX, double mouseY,
            int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record ActionEntry(Component label, Runnable action) {
        public ActionEntry {
            java.util.Objects.requireNonNull(label, "label");
            java.util.Objects.requireNonNull(action, "action");
        }

        public void activate() {
            action.run();
        }
    }
}
