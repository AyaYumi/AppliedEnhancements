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
import com.appliedenhancements.Config;
import com.appliedenhancements.ae2.ForceCraftingMenuBridge;
import com.appliedenhancements.ae2.AelisPlanGraphMenuBridge;
import com.appliedenhancements.client.CraftingPlanAmountFormatter;
import com.appliedenhancements.client.CraftingStorageFormatter;
import com.appliedenhancements.mixin.WidgetContainerAccessor;
import com.appliedenhancements.network.ServerConfigSyncState;
import com.appliedenhancements.plan.AelisPlanGraph;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** A client-only view over AE2's existing menu. Submission and error handling stay in AE2. */
public final class AelisCraftConfirmScreen extends CraftConfirmScreen {
    private static final int INK = 0xFF343343;
    private static final int MUTED = 0xFF767489;
    private static final int PINK = 0xFFE86FA7;
    private static final int CYCLIC_INK = 0xFF8B416B;
    private static final int GRAPH_CYCLE = 0xFFB34D86;
    private static final int GREEN = 0xFF3F754D;
    private static final int RED = 0xFFAD3E59;
    private static final int MISSING_BACKGROUND = 0xFFE7C4CB;
    private static final int MISSING_BACKGROUND_HOVER = 0xFFF1D4D9;
    private static final int GRAPH_TOP = 122;
    private static final int GRAPH_ZOOM_TOP = 101;
    private static final int GRAPH_ZOOM_WIDTH = 91;
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
    private View view = View.LIST;
    private AelisPlanGraph graph;
    private AelisGraphLayout.Drawing graphDrawing;
    private Map<Integer, List<FormattedCharSequence>> graphNodeLines = Map.of();
    private List<FormattedCharSequence> graphHintLines = List.of();
    private AelisPlanLayout.GraphArea graphArea;
    private final AelisGraphViewport graphViewport = new AelisGraphViewport();
    private final Set<Integer> collapsedGraphGroups = new HashSet<>();
    private final AelisGraphInteraction graphInteraction = new AelisGraphInteraction();
    private boolean draggingGraph;
    private double graphLastX;
    private double graphLastY;
    private AE2Button viewToggle;

    private enum View { LIST, TREE }

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
        stopGraphDrag();
        draggingScroll = false;
        layout = AelisPlanLayout.fit(width, height);
        updateGraphHint();
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
        int viewButtonX = layout.tableRight() - 66;
        search = new EditBox(font, leftPos + x + 3, topPos + 78,
                Math.max(12, viewButtonX - x - 7), 15, tr("search"));
        search.visible = viewButtonX - x >= 62;
        search.setMaxLength(100);
        search.setHint(tr("search"));
        search.setTextColor(0xFFEDEBF4);
        search.setValue(query);
        search.setResponder(value -> { query = value; refreshFilter(); });
        addRenderableWidget(search);
        viewToggle = button(viewButtonX, 77, 62, 17, tr("view.tree"), () ->
                setView(view == View.LIST ? View.TREE : View.LIST));
        updateViewToggle();

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
        start.active = cpu.active = bookmark.active = viewToggle.active = false;
        refreshFilter();
        fitGraphToSmallCanvas();
    }

    private void setView(View next) {
        stopGraphDrag();
        draggingScroll = false;
        view = next;
        updateViewToggle();
        if (view == View.TREE) {
            if (menu instanceof AelisPlanGraphMenuBridge bridge) {
                var nextGraph = bridge.appliedenhancements$getPlanGraph();
                if (nextGraph == graph) graphDrawing = buildGraphDrawing();
                else acceptGraph(nextGraph);
                if (graph == null) bridge.appliedenhancements$requestPlanGraph();
            } else {
                graphDrawing = buildGraphDrawing();
            }
        }
    }

    private void updateViewToggle() {
        if (viewToggle == null) return;
        viewToggle.setMessage(tr(view == View.LIST ? "view.tree" : "view.list"));
        viewToggle.setTooltip(Tooltip.create(tr(view == View.LIST ? "view.tree_tip" : "view.list_tip")));
    }

    private void acceptGraph(AelisPlanGraph next) {
        if (next == graph) return;
        stopGraphDrag();
        graphInteraction.clear();
        collapsedGraphGroups.clear();
        graphViewport.reset();
        graph = next;
        graphNodeLines = Map.of();
        updateGraphHint();
        graphDrawing = buildGraphDrawing();
        fitGraphToSmallCanvas();
    }

    private void stopGraphDrag() {
        draggingGraph = false;
        graphInteraction.release();
    }

    private AE2Button button(int x, int y, int w, int h, Component label, Runnable action) {
        return addRenderableWidget(new AE2Button(leftPos + x, topPos + y, w, h, label, ignored -> action.run()));
    }

    @Override
    protected void updateBeforeRender() {
        if (!Config.ENABLE_AELIS_PLAN_SCREEN.get()) {
            switchToScreen(original);
            return;
        }
        super.updateBeforeRender(); // Includes AE2's CraftErrorScreen and retry/replan behavior.
        if (minecraft.screen != this || start == null) return;
        var plan = menu.getPlan();
        var bridge = (AelisCalculationPathMenuBridge) menu;
        // Keep the same view while the initial plan or a replan is being calculated.
        // The default path is AE2_NATIVE until result metadata arrives, so inspect it only with a plan.
        if (plan != null && bridge.molecularmanipulator$getCalculationPath() != AelisCalculationPath.AELIS) {
            switchToScreen(original);
            return;
        }
        if (plan == null) {
            draggingScroll = false;
            if (sources.length != 0) {
                sources = new Object[0];
                rows = List.of();
                cyclicColumn = false;
                refreshFilter();
            }
            acceptGraph(null);
            start.active = cpu.active = bookmark.active = viewToggle.active = false;
            start.setMessage(tr("start"));
            start.setTooltip(Tooltip.create(tr("waiting")));
            cpu.setMessage(tr("waiting"));
            bookmark.setTooltip(Tooltip.create(tr("waiting")));
            return;
        }
        viewToggle.active = true;
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
        if (view == View.TREE && menu instanceof AelisPlanGraphMenuBridge graphBridge) {
            var nextGraph = graphBridge.appliedenhancements$getPlanGraph();
            if (nextGraph == null && (graph != null || changed)) {
                acceptGraph(null);
                graphBridge.appliedenhancements$requestPlanGraph();
            } else if (nextGraph != null && nextGraph != graph) {
                acceptGraph(nextGraph);
            }
        }
        boolean forcing = forceStartRequested();
        start.active = canStart() || forcing;
        start.setMessage(tr(forcing ? "force_start" : "start"));
        start.setTooltip(Tooltip.create(forcing ? tr("force_tip") : canStart() ? tr("start_tip")
                : Component.empty().append(status()).append("\n").append(tr("force_hint"))));
        bookmark.active = OptionalPlanBookmarks.available() && rows.stream().anyMatch(row -> row.missing().signum() > 0);
        bookmark.setTooltip(Tooltip.create(tr(!OptionalPlanBookmarks.available() ? "bookmark_unavailable"
                : bookmark.active ? "bookmark_tip" : "bookmark_none")));
        cpu.active = !plan.isSimulation() || forcing;
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
        for (var entry : plan.getEntries()) {
            entries.put(entry.getWhat(), new long[] {
                    entry.getStoredAmount(), entry.getCraftAmount(), entry.getMissingAmount()});
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

    private AelisGraphLayout.Drawing buildGraphDrawing() {
        if (graph == null) return null;
        var byKey = new HashMap<AEKey, Integer>();
        for (int i = 0; i < graph.keys().size(); i++) byKey.put(graph.keys().get(i), i);
        var matches = new HashSet<Integer>();
        for (var row : visible) {
            Integer id = byKey.get(row.key());
            if (id != null) matches.add(id);
        }
        if (matches.isEmpty() && query.isBlank()) matches.addAll(byKey.values());
        var sizes = new HashMap<Integer, AelisGraphLayout.NodeSize>();
        var labels = new HashMap<Integer, List<FormattedCharSequence>>();
        for (var row : rows) {
            Integer id = byKey.get(row.key());
            if (id == null) continue;
            var quantities = new ArrayList<Component>();
            quantities.add(tr("tree.demand", CraftingPlanAmountFormatter.format(
                    row.key(), row.demand(), AmountFormat.SLOT)));
            quantities.add(tr("tree.craft", CraftingPlanAmountFormatter.format(
                    row.key(), row.craft(), AmountFormat.SLOT)));
            if (row.cyclic().signum() > 0) {
                quantities.add(tr("tree.cyclic_amount", CraftingPlanAmountFormatter.format(
                        row.key(), row.cyclic(), AmountFormat.SLOT))
                        .withStyle(style -> style.withColor(CYCLIC_INK & 0xFFFFFF)));
            }
            int textWidth = Math.max(51, Math.min(140, font.width(row.name())));
            for (var quantity : quantities) textWidth = Math.max(textWidth, font.width(quantity));
            var lines = new ArrayList<>(font.split(Component.literal(row.name()), textWidth));
            for (var quantity : quantities) lines.addAll(font.split(quantity, textWidth));
            labels.put(id, List.copyOf(lines));
            sizes.put(id, new AelisGraphLayout.NodeSize(textWidth + 29,
                    8 + lines.size() * (font.lineHeight + 2) - 2));
        }
        graphNodeLines = Map.copyOf(labels);
        return AelisGraphLayout.build(graph, matches, collapsedGraphGroups, sizes);
    }

    private void updateGraphHint() {
        graphHintLines = font.split(tr(graph != null && !graph.complete() ? "tree.truncated" : "tree.hint"),
                Math.max(1, layout.tableRight() - 26));
        graphArea = layout.graphArea(GRAPH_TOP, graphHintLines.size(), font.lineHeight);
    }

    private int graphHintTop() { return graphArea.hintTop(); }

    private int graphCanvasBottom() { return graphArea.bottom(); }

    private void fitGraphToSmallCanvas() {
        if (graphDrawing == null || graphArea.inlineHint()) return;
        var root = graphInteraction.boxes(graphDrawing).stream()
                .filter(box -> box.node() == graph.root()).findFirst().orElse(null);
        if (root == null) return;
        double scale = Math.min(graphViewport.zoom(),
                (double) (graphCanvasBottom() - GRAPH_TOP - 4) / root.height());
        graphViewport.zoom(scale / graphViewport.zoom(), 0, 0);
        graphViewport.pan(20 - root.x() * graphViewport.zoom() - graphViewport.x(),
                2 - root.y() * graphViewport.zoom() - graphViewport.y());
    }

    private boolean inGraphHelp(double mx, double my) {
        return !graphArea.inlineHint() && mx >= leftPos + 18
                && mx < leftPos + 18 + font.width(tr("tree.help"))
                && my >= topPos + GRAPH_ZOOM_TOP && my < topPos + GRAPH_ZOOM_TOP + 16;
    }

    private void drawTree(GuiGraphics g) {
        int canvasRight = layout.tableRight() - 2;
        int canvasBottom = graphCanvasBottom();
        g.fill(14, 98, canvasRight, layout.footerTop() - 4, 0xFFB0AFBF);
        if (graphArea.inlineHint()) {
            for (int i = 0; i < graphHintLines.size(); i++) {
                g.drawString(font, graphHintLines.get(i), 18, graphHintTop() + i * (font.lineHeight + 1),
                        graph != null && !graph.complete() ? RED : MUTED, false);
            }
        } else {
            text(g, tr("tree.help"), 18, GRAPH_ZOOM_TOP + 4,
                    graph != null && !graph.complete() ? RED : MUTED);
        }
        if (graph == null || graphDrawing == null) {
            int y = GRAPH_TOP + 4;
            for (var line : font.split(tr("tree.loading"), canvasRight - 38)) {
                if (y + font.lineHeight > canvasBottom) break;
                g.drawString(font, line, 20, y, MUTED, false);
                y += font.lineHeight + 1;
            }
            return;
        }
        g.enableScissor(leftPos + 14, topPos + GRAPH_TOP, leftPos + canvasRight, topPos + canvasBottom);
        g.pose().pushPose();
        g.pose().translate(graphViewport.x(), GRAPH_TOP + graphViewport.y(), 0);
        g.pose().scale((float) graphViewport.zoom(), (float) graphViewport.zoom(), 1);
        var orderedBoxes = graphInteraction.boxes(graphDrawing);
        var boxes = new HashMap<Integer, AelisGraphLayout.Box>();
        for (var box : orderedBoxes) boxes.put(box.node(), box);
        for (var group : graphDrawing.groups()) {
            if (!group.cyclic()) continue;
            var bounds = graphInteraction.groupBounds(group, orderedBoxes);
            g.fill((int) bounds.x(), (int) bounds.y(), (int) (bounds.x() + bounds.width()),
                    (int) (bounds.y() + bounds.height()), 0x22C64D91);
            g.fill((int) bounds.x(), (int) bounds.y(), (int) (bounds.x() + bounds.width()),
                    (int) bounds.y() + 2, GRAPH_CYCLE);
            clipped(g, tr("tree.cycle", group.nodes().size()), (int) bounds.x() + 5,
                    (int) bounds.y() + 5, (int) bounds.width() - 23, GRAPH_CYCLE);
            g.drawString(font, group.collapsed() ? "+" : "−",
                    (int) (bounds.x() + bounds.width() - 12), (int) bounds.y() + 5, GRAPH_CYCLE, false);
        }
        for (var edge : graph.edges()) {
            var consumer = boxes.get(edge.consumer());
            var input = boxes.get(edge.input());
            if (consumer == null || input == null) continue;
            int color = edge.alternative() ? 0xFF927F91 : 0xFF5D5B71;
            double consumerCenterX = consumer.x() + consumer.width() / 2.0;
            double inputCenterX = input.x() + input.width() / 2.0;
            boolean downward = input.y() >= consumer.y();
            line(g, consumerCenterX, consumer.y() + (downward ? consumer.height() : 0),
                    inputCenterX, input.y() + (downward ? 0 : input.height()), color, edge.alternative());
        }
        var byKey = new HashMap<AEKey, AelisPlanRows.Row<AEKey>>();
        for (var row : rows) byKey.put(row.key(), row);
        for (var box : orderedBoxes) {
            var row = byKey.get(graph.keys().get(box.node()));
            if (row == null) continue;
            int x = (int) box.x(), y = (int) box.y();
            int width = box.width(), height = box.height();
            int background = row.missing().signum() > 0 ? MISSING_BACKGROUND : 0xFFC9C7D6;
            g.fill(x, y, x + width, y + height, 0xFF5D5B71);
            g.fill(x + 2, y + 2, x + width - 2, y + height - 2, background);
            g.fill(x + 2, y + 2, x + 4, y + height - 2,
                    row.missing().signum() > 0 ? RED : row.cyclic().signum() > 0 ? GRAPH_CYCLE : 0xFF77758B);
            AEKeyRendering.drawInGui(minecraft, g, x + 5, y + (height - 16) / 2, row.key());
            var lines = graphNodeLines.getOrDefault(box.node(), List.of());
            for (int i = 0; i < lines.size(); i++) {
                g.drawString(font, lines.get(i), x + 25, y + 4 + i * (font.lineHeight + 2), INK, false);
            }
            if (graphDrawing.matches().contains(box.node())) {
                int match = 0xFF63A6CE;
                g.fill(x, y, x + width, y + 1, match);
                g.fill(x, y, x + 1, y + height, match);
                g.fill(x + width - 1, y, x + width, y + height, match);
                g.fill(x, y + height - 1, x + width, y + height, match);
            }
        }
        g.pose().popPose();
        g.disableScissor();
        drawZoomControls(g, canvasRight);
    }

    private void drawZoomControls(GuiGraphics g, int right) {
        int x = right - GRAPH_ZOOM_WIDTH;
        int y = GRAPH_ZOOM_TOP;
        buttonFace(g, x, y, 23, 16);
        buttonFace(g, x + 27, y, 40, 16);
        buttonFace(g, x + 71, y, 20, 16);
        text(g, Component.literal("−"), x + 8, y + 4, INK);
        Component percent = Component.literal(Math.round(graphViewport.zoom() * 100) + "%");
        text(g, percent, x + 47 - font.width(percent) / 2, y + 4, INK);
        text(g, Component.literal("+"), x + 77, y + 4, INK);
    }

    private static void buttonFace(GuiGraphics g, int x, int y, int width, int height) {
        g.fill(x, y, x + width, y + height, 0xFF555366);
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFD0CEDB);
        g.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFFF1EEF7);
        g.fill(x + 1, y + 1, x + 2, y + height - 1, 0xFFF1EEF7);
    }

    private static void line(GuiGraphics g, double x1, double y1, double x2, double y2,
            int color, boolean dashed) {
        double middle = (y1 + y2) / 2.0;
        segment(g, x1, y1, x1, middle, color, dashed);
        segment(g, x1, middle, x2, middle, color, dashed);
        segment(g, x2, middle, x2, y2, color, dashed);
    }

    private static void segment(GuiGraphics g, double x1, double y1, double x2, double y2,
            int color, boolean dashed) {
        if (Math.round(y1) == Math.round(y2)) {
            int start = (int) Math.round(Math.min(x1, x2));
            int end = (int) Math.round(Math.max(x1, x2));
            if (dashed) {
                for (int x = start; x < end; x += 7) g.fill(x, (int) Math.round(y1), Math.min(x + 3, end), (int) Math.round(y1) + 1, color);
            } else g.fill(start, (int) Math.round(y1), end + 1, (int) Math.round(y1) + 1, color);
        } else {
            int start = (int) Math.round(Math.min(y1, y2));
            int end = (int) Math.round(Math.max(y1, y2));
            if (dashed) {
                for (int y = start; y < end; y += 7) g.fill((int) Math.round(x1), y, (int) Math.round(x1) + 1, Math.min(y + 3, end), color);
            } else g.fill((int) Math.round(x1), start, (int) Math.round(x1) + 1, end + 1, color);
        }
    }

    private void refreshFilter() {
        stopGraphDrag();
        visible = AelisPlanRows.filter(rows, filter, query);
        scroll = 0;
        if (graph != null && view == View.TREE) graphDrawing = buildGraphDrawing();
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
        right(g, status(), imageWidth - 15, 17, menu.getPlan() == null ? MUTED : canStart() ? GREEN : RED);
        if (menu.getPlan() == null) {
            drawCalculating(g);
            return;
        }
        var bridge = (AelisCalculationPathMenuBridge) menu;
        var plan = menu.getPlan();
        BigInteger bytes = bridge.appliedenhancements$getBigIntegerBytes();
        if (bytes == null) bytes = BigInteger.valueOf(plan.getUsedBytes());
        text(g, tr("storage", CraftingStorageFormatter.formatBytes(bytes)), 18, 51, INK);
        right(g, tr("materials", rows.size()), imageWidth - 18, 51, INK);
        if (view == View.TREE) {
            drawTree(g);
            return;
        }
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

    private void drawCalculating(GuiGraphics g) {
        Component label = tr("waiting");
        int barWidth = layout.tableRight() - 40;
        int filled = -1;
        if (ServerConfigSyncState.isProgressDisplayEnabled()
                && menu instanceof CraftingCalculationProgressMenuBridge bridge) {
            var progress = bridge.molecularmanipulator$getCalculationProgress();
            if (progress.active()) {
                label = Component.empty().append(label).append(" · ").append(Component.translatable(
                        "gui.appliedenhancements.calculation_progress.phase."
                                + progress.phase().name().toLowerCase(Locale.ROOT)));
                if (progress.hasKnownTotal()) {
                    filled = progress.totalUnits() == 0 ? barWidth : (int) Math.min(barWidth,
                            (double) progress.completedUnits() * barWidth / progress.totalUnits());
                }
            }
        }
        clipped(g, label, 20, 125, barWidth, MUTED);
        g.fill(20, 141, 20 + barWidth, 144, 0xFF858297);
        if (filled >= 0) {
            g.fill(20, 141, 20 + filled, 144, PINK);
        } else {
            int travel = barWidth - 32;
            long phase = (Util.getMillis() / 18L) % (travel * 2L);
            int x = 20 + (int) (phase <= travel ? phase : travel * 2L - phase);
            g.fill(x, 141, x + 32, 144, PINK);
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
        if (view == View.TREE && inGraphHelp(mx, my)) {
            var tips = new ArrayList<Component>();
            tips.add(tr("tree.hint"));
            if (graph != null && !graph.complete()) tips.add(tr("tree.truncated"));
            g.renderComponentTooltip(font, tips, mx, my);
            return;
        }
        var row = view == View.TREE ? treeRowUnderMouse(mx, my) : rowUnderMouse(mx, my);
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
        if (view == View.TREE) {
            var box = treeNodeUnderMouse(mx, my);
            var row = box == null ? null : treeRow(box);
            if (row == null) return null;
            double x = leftPos + graphViewport.x() + box.x() * graphViewport.zoom();
            double y = topPos + GRAPH_TOP + graphViewport.y() + box.y() * graphViewport.zoom();
            int left = (int) Math.max(leftPos + 14, Math.floor(x));
            int top = (int) Math.max(topPos + GRAPH_TOP, Math.floor(y));
            int right = (int) Math.min(leftPos + layout.tableRight() - 2,
                    Math.ceil(x + box.width() * graphViewport.zoom()));
            int bottom = (int) Math.min(topPos + graphCanvasBottom(),
                    Math.ceil(y + box.height() * graphViewport.zoom()));
            return new StackWithBounds(new GenericStack(row.key(), 1),
                    new Rect2i(left, top, Math.max(1, right - left), Math.max(1, bottom - top)));
        }
        var row = rowUnderMouse(mx, my);
        if (row == null) return null;
        int y = topPos + AelisPlanLayout.ROW_TOP
                + ((int) (my - topPos) - AelisPlanLayout.ROW_TOP) / AelisPlanLayout.ROW_HEIGHT * AelisPlanLayout.ROW_HEIGHT;
        return new StackWithBounds(new GenericStack(row.key(), 1),
                new Rect2i(leftPos + 14, y, layout.tableRight() - 25, AelisPlanLayout.ROW_HEIGHT));
    }

    private AelisPlanRows.Row<AEKey> treeRowUnderMouse(double mx, double my) {
        var box = treeNodeUnderMouse(mx, my);
        return box == null ? null : treeRow(box);
    }

    private AelisPlanRows.Row<AEKey> treeRow(AelisGraphLayout.Box box) {
        AEKey key = graph.keys().get(box.node());
        return rows.stream().filter(row -> row.key().equals(key)).findFirst().orElse(null);
    }

    private AelisGraphLayout.Box treeNodeUnderMouse(double mx, double my) {
        if (graph == null || graphDrawing == null || !inGraphCanvas(mx, my)) return null;
        return graphInteraction.nodeAt(graphDrawing, graphViewport.worldX(mx - leftPos),
                graphViewport.worldY(my - topPos - GRAPH_TOP));
    }

    private boolean inGraphCanvas(double mx, double my) {
        return mx >= leftPos + 14 && mx < leftPos + layout.tableRight() - 2
                && my >= topPos + GRAPH_TOP && my < topPos + graphCanvasBottom();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (view == View.TREE && mx >= leftPos + 14 && mx < leftPos + layout.tableRight()
                && my >= topPos + 98 && my < topPos + layout.footerTop()) {
            if (dy == 0) return true;
            if (!graphInteraction.dragging()) graphViewport.zoom(dy > 0 ? 1.1 : 0.9,
                    mx - leftPos, my - topPos - GRAPH_TOP);
            return true;
        }
        if (mx >= leftPos + 10 && mx < leftPos + layout.tableRight()
                && my >= topPos + 74 && my < topPos + layout.footerTop()) {
            scroll = AelisPlanRows.clampScroll(scroll - (int) Math.signum(dy) * 3, visible.size(), layout.visibleRows());
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (view == View.TREE && button == 0) {
            int zoomX = leftPos + layout.tableRight() - 2 - GRAPH_ZOOM_WIDTH;
            int zoomY = topPos + GRAPH_ZOOM_TOP;
            if (my >= zoomY && my < zoomY + 16 && mx >= zoomX && mx < zoomX + 23) {
                graphViewport.zoom(0.9, layout.tableRight() / 2.0,
                        (graphCanvasBottom() - GRAPH_TOP) / 2.0);
                return true;
            }
            if (my >= zoomY && my < zoomY + 16 && mx >= zoomX + 71 && mx < zoomX + 91) {
                graphViewport.zoom(1.1, layout.tableRight() / 2.0,
                        (graphCanvasBottom() - GRAPH_TOP) / 2.0);
                return true;
            }
        }
        if (view == View.TREE && button == 0 && graphDrawing != null && inGraphCanvas(mx, my)) {
            double wx = graphViewport.worldX(mx - leftPos);
            double wy = graphViewport.worldY(my - topPos - GRAPH_TOP);
            if (graphInteraction.beginDrag(graphDrawing, wx, wy)) {
                draggingGraph = false;
                return true;
            }
            var boxes = graphInteraction.boxes(graphDrawing);
            for (int i = graphDrawing.groups().size() - 1; i >= 0; i--) {
                var group = graphDrawing.groups().get(i);
                var bounds = graphInteraction.groupBounds(group, boxes);
                if (group.cyclic() && new AelisGraphInteraction.Bounds(
                        bounds.x(), bounds.y(), bounds.width(), 18).contains(wx, wy)) {
                    int id = group.nodes().getFirst();
                    if (!collapsedGraphGroups.add(id)) collapsedGraphGroups.remove(id);
                    graphDrawing = buildGraphDrawing();
                    return true;
                }
            }
        }
        if (view == View.TREE && button == 0 && inGraphCanvas(mx, my)) {
            draggingGraph = true;
            graphLastX = mx;
            graphLastY = my;
            return true;
        }
        if (view == View.LIST && button == 0 && visible.size() > layout.visibleRows()
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
        if (view == View.TREE && graphInteraction.dragging() && button == 0) {
            graphInteraction.dragTo(graphViewport.worldX(mx - leftPos),
                    graphViewport.worldY(my - topPos - GRAPH_TOP));
            return true;
        }
        if (view == View.TREE && draggingGraph && button == 0) {
            graphViewport.pan(mx - graphLastX, my - graphLastY);
            graphLastX = mx;
            graphLastY = my;
            return true;
        }
        if (draggingScroll && button == 0) { scrollToMouse(my); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && (draggingGraph || graphInteraction.dragging())) { stopGraphDrag(); return true; }
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
            // Keyboard and mouse share the same readiness gate.
            startSafely();
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
