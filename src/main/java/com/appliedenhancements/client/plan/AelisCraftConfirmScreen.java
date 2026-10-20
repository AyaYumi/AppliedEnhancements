package com.appliedenhancements.client.plan;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.AE2Button;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.ForceCraftingMenuBridge;
import com.appliedenhancements.client.CraftingPlanAmountFormatter;
import com.appliedenhancements.client.CraftingStorageFormatter;
import com.appliedenhancements.mixin.WidgetContainerAccessor;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** A client-only view over AE2's existing menu. Submission and error handling stay in AE2. */
public final class AelisCraftConfirmScreen extends CraftConfirmScreen {
    private static final int INK = 0xFF343343;
    private static final int MUTED = 0xFF767489;
    private static final int PINK = 0xFFE86FA7;
    private static final int CYCLIC_INK = 0xFF8B416B;
    private static final int GREEN = 0xFF3F754D;
    private static final int RED = 0xFFAD3E59;
    private static final int MISSING_BACKGROUND = 0xFFE7C4CB;
    private static final int MISSING_BACKGROUND_HOVER = 0xFFF1D4D9;
    private static final String PREFIX = "gui.appliedenhancements.plan.";
    private static final ResourceLocation MARK = AppliedEnhancements.id("textures/gui/aelis_mark.png");
    private final CraftConfirmScreen original;
    private AelisPlanLayout layout;
    private AelisPlanRows.Filter filter = AelisPlanRows.Filter.ALL;
    private String query = "";
    private List<AelisPlanRows.Row<AEKey>> rows = List.of();
    private List<AelisPlanRows.Row<AEKey>> visible = List.of();
    private Object[] sources = new Object[0];
    private final Map<AelisPlanRows.Filter, PlanFilterButton> filterButtons = new LinkedHashMap<>();
    private EditBox search;
    private AE2Button start;
    private AE2Button cpu;
    private AE2Button bookmark;
    private int scroll;
    private boolean draggingScroll;
    private boolean cyclicColumn;

    public AelisCraftConfirmScreen(CraftConfirmScreen original, Inventory inventory) {
        super(original.getMenu(), inventory, tr("title"),
                StyleManager.loadStyleDoc("/screens/appliedenhancements/aelis_craft_confirm.json"));
        this.original = original;
        // The superclass retains its private controls for status/error updates. They are not rendered
        // or wired to input in this view. The original instance remains completely untouched.
        var access = (WidgetContainerAccessor) widgets;
        access.appliedenhancements$getWidgets().clear();
        access.appliedenhancements$getCompositeWidgets().clear();
        style.getText().keySet().forEach(id -> setTextHidden(id, true));
    }

    @Override
    protected boolean shouldAddToolbar() { return false; }

    @Override
    protected void init() {
        layout = AelisPlanLayout.fit(width, height);
        imageWidth = layout.width();
        imageHeight = layout.height();
        super.init();
        // Very large GUI scales can become narrower than a four-column table. Preserve usability.
        if (!layout.usable()) {
            AelisPlanScreenEvents.showOriginal(this, original);
            return;
        }
        filterButtons.clear();
        int x = 14;
        for (var value : AelisPlanRows.Filter.values()) {
            int buttonWidth = value == AelisPlanRows.Filter.ALL ? 37 : 47;
            var button = addRenderableWidget(new PlanFilterButton(leftPos + x, topPos + 77, buttonWidth, 17,
                    tr("filter." + value.name().toLowerCase(Locale.ROOT)),
                    () -> { filter = value; refreshFilter(); }));
            filterButtons.put(value, button);
            x += buttonWidth + 3;
        }
        search = new EditBox(font, leftPos + x + 3, topPos + 78,
                layout.tableRight() - x - 12, 15, tr("search"));
        search.setMaxLength(100);
        search.setHint(tr("search"));
        search.setTextColor(0xFFEDEBF4);
        search.setValue(query);
        search.setResponder(value -> { query = value; refreshFilter(); });
        addRenderableWidget(search);

        cpu = button(14, layout.cpuButtonTop(), imageWidth - 28, AelisPlanLayout.FOOTER_BUTTON_HEIGHT, tr("cpu.auto"),
                () -> menu.cycleSelectedCPU(!isHandlingRightClick()));
        cpu.setTooltip(Tooltip.create(tr("cpu.tip")));
        int actions = layout.actionButtonTop();
        button(14, actions, 52, AelisPlanLayout.FOOTER_BUTTON_HEIGHT, tr("cancel"), menu::goBack);
        button(70, actions, 73, AelisPlanLayout.FOOTER_BUTTON_HEIGHT, tr("original"),
                () -> AelisPlanScreenEvents.showOriginal(this, original));
        bookmark = button(147, actions, imageWidth - 261, AelisPlanLayout.FOOTER_BUTTON_HEIGHT,
                tr("bookmark"), this::bookmarkMissing);
        start = button(imageWidth - 110, actions, 96, AelisPlanLayout.FOOTER_BUTTON_HEIGHT, tr("start"), this::startSafely);
        refreshFilter();
    }

    private AE2Button button(int x, int y, int w, int h, Component label, Runnable action) {
        return addRenderableWidget(new AE2Button(leftPos + x, topPos + y, w, h, label, ignored -> action.run()));
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender(); // Includes AE2's CraftErrorScreen and retry/replan behavior.
        if (minecraft.screen != this || start == null) return;
        var plan = menu.getPlan();
        var bridge = (AelisCalculationPathMenuBridge) menu;
        // Replanning (including from AE2's submit-error screen) must go through the native
        // calculating view again. A fallback result must never retain an AELIS engine label.
        if (plan == null || bridge.molecularmanipulator$getCalculationPath() != AelisCalculationPath.AELIS) {
            switchToScreen(original);
            return;
        }
        var craft = bridge.appliedenhancements$getBigIntegerCraftAmounts();
        var stored = bridge.appliedenhancements$getBigIntegerStoredAmounts();
        var missing = bridge.appliedenhancements$getBigIntegerMissingAmounts();
        var cyclic = bridge.appliedenhancements$getBigIntegerCyclicCraftAmounts();
        Object[] nextSources = {plan, craft, stored, missing, cyclic};
        boolean changed = sources.length != nextSources.length;
        for (int i = 0; !changed && i < sources.length; i++) changed = sources[i] != nextSources[i];
        if (changed) {
            sources = nextSources;
            rebuildRows(plan, craft, stored, missing, cyclic);
        }
        boolean forcing = forceStartRequested();
        start.active = canStart() || forcing;
        start.setMessage(tr(forcing ? "force_start" : "start"));
        start.setTooltip(Tooltip.create(forcing ? tr("force_tip") : canStart() ? tr("start_tip")
                : Component.empty().append(status()).append("\n").append(tr("force_hint"))));
        bookmark.active = OptionalPlanBookmarks.available() && rows.stream().anyMatch(row -> row.missing().signum() > 0);
        bookmark.setTooltip(Tooltip.create(tr(!OptionalPlanBookmarks.available() ? "bookmark_unavailable"
                : bookmark.active ? "bookmark_tip" : "bookmark_none")));
        cpu.active = plan != null && (!plan.isSimulation() || forcing);
        Component selected = menu.hasNoCPU() ? tr("cpu.none")
                : menu.cpuName == null ? tr("cpu.auto") : menu.cpuName;
        Component details = menu.cpuName == null || menu.hasNoCPU() ? Component.empty()
                : tr("cpu.capacity", CraftingStorageFormatter.formatBytes(menu.getCpuAvailableBytes()),
                        menu.getCpuCoProcessors());
        cpu.setMessage(tr("cpu.selected", selected, details));
    }

    private void rebuildRows(CraftingPlanSummary plan, Map<AEKey, BigInteger> craft,
            Map<AEKey, BigInteger> stored, Map<AEKey, BigInteger> missing, Map<AEKey, BigInteger> cyclic) {
        var entries = new LinkedHashMap<AEKey, long[]>();
        if (plan != null) {
            for (var entry : plan.getEntries()) {
                entries.put(entry.getWhat(), new long[] {
                        entry.getStoredAmount(), entry.getCraftAmount(), entry.getMissingAmount()});
            }
        }
        for (var amounts : List.of(craft, stored, missing, cyclic)) {
            amounts.keySet().forEach(key -> entries.putIfAbsent(key, new long[3]));
        }
        var result = new ArrayList<AelisPlanRows.Row<AEKey>>(entries.size());
        for (var entry : entries.entrySet()) {
            var key = entry.getKey();
            var amount = entry.getValue();
            result.add(new AelisPlanRows.Row<>(key, AEKeyRendering.getDisplayName(key).getString(),
                    key.toString(), AelisPlanRows.exact(stored, key, amount[0]),
                    AelisPlanRows.exact(craft, key, amount[1]), AelisPlanRows.exact(missing, key, amount[2]),
                    AelisPlanRows.exact(cyclic, key, 0)));
        }
        rows = List.copyOf(result);
        cyclicColumn = AelisPlanRows.hasCyclicAmounts(rows);
        refreshFilter();
    }

    private void refreshFilter() {
        visible = AelisPlanRows.filter(rows, filter, query);
        scroll = 0;
        filterButtons.forEach((value, button) -> button.selected = value == filter);
    }

    private boolean canStart() {
        var plan = menu.getPlan();
        return AelisPlanRows.canStart(plan != null, plan != null && plan.isSimulation(), menu.hasNoCPU());
    }

    private boolean forceStartRequested() {
        return menu.getPlan() != null && !canStart() && hasShiftDown();
    }

    private void startSafely() {
        if (canStart()) menu.startJob();
        else if (forceStartRequested()) ((ForceCraftingMenuBridge) menu).appliedenhancements$startForced();
    }

    private void bookmarkMissing() {
        var keys = rows.stream().filter(row -> row.missing().signum() > 0)
                .map(AelisPlanRows.Row::key).distinct().toList();
        if (keys.isEmpty() || !OptionalPlanBookmarks.available()) return;
        var result = OptionalPlanBookmarks.add(keys);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(tr("bookmark_added", result.added()), true);
            if (result.unsupported() > 0) minecraft.player.displayClientMessage(
                    tr("bookmark_unsupported", result.unsupported()), false);
        }
    }

    private Component status() {
        var plan = menu.getPlan();
        return tr(plan == null ? "waiting" : plan.isSimulation() ? "missing" : menu.hasNoCPU() ? "no_cpu" : "ready");
    }

    @Override
    public void drawBG(GuiGraphics g, int ox, int oy, int mx, int my, float partialTicks) {
        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        g.fill(2, 3, imageWidth + 2, imageHeight + 3, 0x70000000);
        bevel(g, 0, 0, imageWidth, imageHeight, 0xFFC3C2D1, false);
        g.fill(4, 4, imageWidth - 4, 37, 0xFFCDCCDA);
        g.fill(10, 37, imageWidth - 10, 38, 0xFF9592A9);
        bevel(g, 10, 42, imageWidth - 20, 27, 0xFFB5B4C6, true);
        bevel(g, 10, 74, layout.tableRight() - 10, layout.footerTop() - 78, 0xFFAEADBF, true);
        g.fill(13, 97, layout.tableRight() - 3, 108, 0xFF9897AB);
        bevel(g, 10, layout.footerTop(), imageWidth - 20, AelisPlanLayout.FOOTER_HEIGHT, 0xFFB6B5C7, true);
        g.pose().popPose();
    }

    @Override
    public void drawFG(GuiGraphics g, int ox, int oy, int mx, int my) {
        drawMark(g, 14, 10);
        text(g, tr("title"), 46, 11, INK);
        text(g, tr("subtitle"), 46, 24, MUTED);
        right(g, status(), imageWidth - 15, 17, canStart() ? GREEN : RED);
        var bridge = (AelisCalculationPathMenuBridge) menu;
        var plan = menu.getPlan();
        BigInteger bytes = bridge.appliedenhancements$getBigIntegerBytes();
        if (bytes == null) bytes = BigInteger.valueOf(plan == null ? 0 : plan.getUsedBytes());
        text(g, tr("storage", CraftingStorageFormatter.formatBytes(bytes)), 18, 51, INK);
        right(g, tr("materials", rows.size()), imageWidth - 18, 51, INK);
        var columns = layout.columns(cyclicColumn);
        int end = columns.right();
        text(g, tr("material"), 18, 99, INK);
        right(g, tr("demand"), columns.demandRight(), 99, INK);
        right(g, tr("stored"), columns.storedRight(), 99, INK);
        right(g, tr("craft"), columns.craftRight(), 99, INK);
        if (cyclicColumn) right(g, tr("cyclic_craft"), end, 99, CYCLIC_INK);
        int localX = mx - ox;
        int localY = my - oy;
        for (int i = 0; i < layout.visibleRows() && scroll + i < visible.size(); i++) {
            var row = visible.get(scroll + i);
            int y = AelisPlanLayout.ROW_TOP + i * AelisPlanLayout.ROW_HEIGHT;
            boolean hover = localX >= 14 && localX < end + 3 && localY >= y && localY < y + 22;
            boolean missing = row.missing().signum() > 0;
            int background = missing ? (hover ? MISSING_BACKGROUND_HOVER : MISSING_BACKGROUND)
                    : hover ? 0xFFDCD5E6 : i % 2 == 0 ? 0xFFC6C5D4 : 0xFFBDBCCD;
            g.fill(14, y, end + 3, y + 22, background);
            if (missing) g.fill(14, y + 1, 16, y + 21, RED);
            else if (row.cyclic().signum() > 0) g.fill(14, y + 1, 16, y + 21, PINK);
            AEKeyRendering.drawInGui(minecraft, g, 19, y + 3, row.key());
            clipped(g, Component.literal(row.name()), 40, y + 7, columns.nameWidth(), INK);
            amount(g, row.key(), row.demand(), columns.demandRight(), y + 7, INK);
            amount(g, row.key(), row.stored(), columns.storedRight(), y + 7, MUTED);
            amount(g, row.key(), row.craft(), columns.craftRight(), y + 7, row.craft().signum() > 0 ? INK : MUTED);
            if (cyclicColumn) amount(g, row.key(), row.cyclic(), end, y + 7,
                    row.cyclic().signum() > 0 ? CYCLIC_INK : MUTED);
        }
        if (visible.isEmpty()) {
            clipped(g, tr("empty"), 20, 125, layout.tableRight() - 40, MUTED);
        }
        drawScroll(g);
        int countY = layout.footerTop() - 14;
        // Only use free space after the final row, never overprint a material.
        if (countY >= layout.rowsBottom() + 2) {
            text(g, tr("shown", visible.size(), rows.size()), 18, countY, MUTED);
        }
    }

    private void drawScroll(GuiGraphics g) {
        int x = layout.tableRight() - 8;
        int h = layout.rowsBottom() - AelisPlanLayout.ROW_TOP;
        g.fill(x, AelisPlanLayout.ROW_TOP, x + 4, layout.rowsBottom(), 0xFF858297);
        if (visible.size() <= layout.visibleRows()) return;
        int thumb = Math.max(12, h * layout.visibleRows() / visible.size());
        int y = AelisPlanLayout.ROW_TOP + (h - thumb) * scroll / (visible.size() - layout.visibleRows());
        g.fill(x, y, x + 4, y + thumb, 0xFFF0CADF);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTicks) {
        super.render(g, mx, my, partialTicks);
        if (minecraft.screen != this) return;
        var row = rowUnderMouse(mx, my);
        if (row == null) return;
        var tooltip = new ArrayList<>(AEKeyRendering.getTooltip(row.key()));
        for (var value : List.of(Map.entry("demand", row.demand()), Map.entry("stored", row.stored()),
                Map.entry("craft", row.craft()), Map.entry("missing_amount", row.missing()),
                Map.entry("cyclic_amount", row.cyclic()))) {
            tooltip.add(tr("tooltip." + value.getKey(), CraftingPlanAmountFormatter.format(
                    row.key(), value.getValue(), AmountFormat.FULL)).withStyle(ChatFormatting.GRAY));
        }
        g.renderComponentTooltip(font, tooltip, mx, my);
    }

    private AelisPlanRows.Row<AEKey> rowUnderMouse(double mx, double my) {
        double x = mx - leftPos;
        double y = my - topPos;
        if (x < 14 || x >= layout.tableRight() - 11 || y < AelisPlanLayout.ROW_TOP
                || y >= layout.rowsBottom()) return null;
        int index = scroll + (int) (y - AelisPlanLayout.ROW_TOP) / AelisPlanLayout.ROW_HEIGHT;
        return index < visible.size() ? visible.get(index) : null;
    }

    @Override
    public StackWithBounds getStackUnderMouse(double mx, double my) {
        var row = rowUnderMouse(mx, my);
        if (row == null) return null;
        int y = topPos + AelisPlanLayout.ROW_TOP
                + ((int) (my - topPos) - AelisPlanLayout.ROW_TOP) / AelisPlanLayout.ROW_HEIGHT * AelisPlanLayout.ROW_HEIGHT;
        return new StackWithBounds(new GenericStack(row.key(), 1),
                new Rect2i(leftPos + 14, y, layout.tableRight() - 25, AelisPlanLayout.ROW_HEIGHT));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (mx >= leftPos + 10 && mx < leftPos + layout.tableRight()
                && my >= topPos + 74 && my < topPos + layout.footerTop()) {
            scroll = AelisPlanRows.clampScroll(scroll - (int) Math.signum(dy) * 3, visible.size(), layout.visibleRows());
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && visible.size() > layout.visibleRows()
                && mx >= leftPos + layout.tableRight() - 10 && mx < leftPos + layout.tableRight() - 2
                && my >= topPos + AelisPlanLayout.ROW_TOP && my < topPos + layout.rowsBottom()) {
            draggingScroll = true;
            scrollToMouse(my);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingScroll && button == 0) { scrollToMouse(my); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && draggingScroll) { draggingScroll = false; return true; }
        return super.mouseReleased(mx, my, button);
    }

    private void scrollToMouse(double my) {
        int h = layout.rowsBottom() - AelisPlanLayout.ROW_TOP;
        int thumb = Math.max(12, h * layout.visibleRows() / visible.size());
        double ratio = (my - topPos - AelisPlanLayout.ROW_TOP - thumb / 2.0) / Math.max(1, h - thumb);
        scroll = AelisPlanRows.clampScroll((int) Math.round(ratio * (visible.size() - layout.visibleRows())),
                visible.size(), layout.visibleRows());
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (search != null && search.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                setFocused(null);
                search.setFocused(false);
            } else {
                search.keyPressed(key, scanCode, modifiers);
            }
            // Do not let inventory bindings (e.g. E) close the menu while typing.
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            // Enter in search never submits an order; keyboard and mouse share the same readiness gate.
            if (search == null || !search.isFocused()) startSafely();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void amount(GuiGraphics g, AEKey key, BigInteger amount, int end, int y, int color) {
        var label = Component.literal(CraftingPlanAmountFormatter.format(key, amount, AmountFormat.SLOT));
        int w = Math.min(44, font.width(label));
        clipped(g, label, end - w, y, w, color);
    }

    private void clipped(GuiGraphics g, Component value, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) return;
        String text = value.getString();
        if (font.width(text) > maxWidth) text = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
        g.drawString(font, text, x, y, color, false);
    }

    private void text(GuiGraphics g, Component value, int x, int y, int color) {
        g.drawString(font, value, x, y, color, false);
    }

    private void right(GuiGraphics g, Component value, int end, int y, int color) {
        text(g, value, end - font.width(value), y, color);
    }

    private static MutableComponent tr(String key, Object... values) { return Component.translatable(PREFIX + key, values); }

    private static void bevel(GuiGraphics g, int x, int y, int w, int h, int color, boolean inset) {
        g.fill(x, y, x + w, y + h, 0xFF555366);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
        int light = inset ? 0xFF8C899F : 0xFFF1EEF7;
        int dark = inset ? 0xFFE2DFEE : 0xFF858196;
        g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, light);
        g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, dark);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, dark);
    }

    private static void drawMark(GuiGraphics g, int x, int y) {
        // Sample the whole mark, keeping the AE / LIS spacing and frame padding at every GUI scale.
        g.blit(MARK, x, y, 24, 24, 0.0F, 0.0F, 48, 48, 48, 48);
    }

    /** Keep the active filter visible using AE2's own highlight; rendering stays entirely native. */
    private static final class PlanFilterButton extends AE2Button {
        private boolean selected;

        private PlanFilterButton(int x, int y, int width, int height, Component label, Runnable action) {
            super(x, y, width, height, label, ignored -> action.run());
        }

        @Override
        public boolean isHovered() {
            return selected || super.isHovered();
        }
    }
}
