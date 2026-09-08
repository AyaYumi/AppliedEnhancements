package com.appliedenhancements.mixin;

import appeng.client.Point;
import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import appeng.client.gui.me.patternaccess.PatternSlot;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.Scrollbar;
import appeng.menu.implementations.PatternAccessTermMenu;
import com.appliedenhancements.api.PatternSlotRef;
import com.appliedenhancements.api.PatternTerminalIntegrationApi;
import com.appliedenhancements.api.PatternTerminalIntegrationApi.Family;
import com.appliedenhancements.api.client.PatternQuickMoveSession;
import com.appliedenhancements.client.pattern.DuplicatePatternIndex;
import com.appliedenhancements.client.pattern.DuplicatePatternInteraction;
import com.appliedenhancements.client.pattern.DuplicatePatternRows;
import com.appliedenhancements.client.pattern.DuplicatePatternRows.InvalidSourcePattern;
import com.appliedenhancements.client.pattern.DuplicatePatternRows.PatternSource;
import com.appliedenhancements.client.pattern.DuplicatePatternSourceDisplay;
import com.appliedenhancements.client.pattern.DuplicatePatternToggleButton;
import com.appliedenhancements.client.pattern.InvalidPatternToggleButton;
import com.appliedenhancements.client.pattern.PatternHeaderActionButton;
import com.appliedenhancements.client.pattern.PatternHeaderActionButton.Action;
import com.appliedenhancements.client.pattern.PatternHeaderActionButton.Hit;
import com.appliedenhancements.client.pattern.PatternQuickMoveContextMenu;
import com.appliedenhancements.client.pattern.PatternQuickMoveContextMenu.Entry;
import com.appliedenhancements.client.pattern.PatternTerminalRowFactory;
import com.appliedenhancements.client.menu.ItemContextMenuKeyMapping;
import com.appliedenhancements.client.pattern.QuickPatternMoveToggleButton;
import com.appliedenhancements.integration.ae2.PatternQuickMoveScreenBridge;
import com.appliedenhancements.integration.ae2.DuplicatePatternSourceScreenBridge;
import com.appliedenhancements.integration.ae2.PatternTerminalGroupHeaderBridge;
import com.appliedenhancements.integration.ae2.PatternTerminalSlotsRowBridge;
import com.appliedenhancements.integration.ae2.ScreenWidgetBridge;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pattern filters and Quick Move for AE2's wired and AE2WTLib wireless terminals. */
@Mixin(value = PatternAccessTermScreen.class, remap = false)
public abstract class PatternAccessTermScreenDuplicateMixin
        implements PatternQuickMoveScreenBridge, DuplicatePatternSourceScreenBridge {
    @Unique
    private static final String appliedenhancements$AE2_SCREEN =
            "appeng.client.gui.me.patternaccess.PatternAccessTermScreen";
    @Unique
    private static final String appliedenhancements$AE2WTLIB_SCREEN =
            "de.mari_023.ae2wtlib.wat.WATScreen";
    @Unique
    private static final int appliedenhancements$ORIGINAL_HEADER_HEIGHT = 17;
    @Unique
    private static final int appliedenhancements$TOOLBAR_ROW_HEIGHT = 18;
    @Unique
    private static final int appliedenhancements$EXPANDED_HEADER_HEIGHT = 35;
    @Unique
    private static final int appliedenhancements$HEADER_INTERIOR_SAMPLE_Y = 8;
    @Unique
    private static final ResourceLocation appliedenhancements$PATTERN_TERMINAL_TEXTURE =
            new ResourceLocation(
                    "ae2", "textures/guis/patternaccessterminal.png");

    @Shadow
    @Final
    private HashMap<Long, PatternContainerRecord> byId;

    @Shadow
    @Final
    private ArrayList<Object> rows;

    @Shadow
    @Final
    private AETextField searchField;

    @Shadow
    @Final
    private Scrollbar scrollbar;

    @Shadow
    private int visibleRows;

    @Unique
    private boolean appliedenhancements$duplicatesOnly;
    @Unique
    private boolean appliedenhancements$invalidOnly;
    @Unique
    private Map<PatternSlotRef, PatternSlotRef> appliedenhancements$displayToSource = Map.of();
    @Unique
    private Map<PatternSlotRef, PatternSource> appliedenhancements$displaySources = Map.of();
    @Unique
    private DuplicatePatternToggleButton appliedenhancements$duplicateButton;
    @Unique
    private InvalidPatternToggleButton appliedenhancements$invalidButton;
    @Unique
    private PatternTerminalRowFactory appliedenhancements$rowFactory;
    @Unique
    private PatternQuickMoveSession appliedenhancements$quickMove;
    @Unique
    private QuickPatternMoveToggleButton appliedenhancements$quickMoveButton;
    @Unique
    private List<Hit> appliedenhancements$headerHits = List.of();
    @Unique
    private PatternQuickMoveContextMenu appliedenhancements$contextMenu;

    @Invoker("refreshList")
    protected abstract void appliedenhancements$refreshList();

    @Invoker("resetScrollbar")
    protected abstract void appliedenhancements$resetScrollbar();

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
            method = { "init", "m_7856_" },
            at = @At(value = "INVOKE", target = "Lappeng/api/config/TerminalStyle;getRows(I)I"))
    private int appliedenhancements$keepRowsAtLargeGuiScale(int rows) {
        return appliedenhancements$isSupportedScreen() ? Math.max(2, rows) : rows;
    }

    @ModifyConstant(method = { "init", "m_7856_" }, constant = @Constant(intValue = 17))
    private int appliedenhancements$includeToolbarInAvailableHeight(int original) {
        return appliedenhancements$isSupportedScreen()
                ? appliedenhancements$EXPANDED_HEADER_HEIGHT
                : original;
    }

    @ModifyConstant(method = { "init", "m_7856_" }, constant = @Constant(intValue = 114))
    private int appliedenhancements$includeToolbarInImageHeight(int original) {
        return appliedenhancements$isSupportedScreen()
                ? original + appliedenhancements$TOOLBAR_ROW_HEIGHT
                : original;
    }

    @ModifyConstant(method = "drawFG", constant = @Constant(intValue = 23))
    private int appliedenhancements$shiftGroupHeaderContentDown(int original) {
        return appliedenhancements$isSupportedScreen()
                ? original + appliedenhancements$TOOLBAR_ROW_HEIGHT
                : original;
    }

    @ModifyConstant(method = "drawBG", constant = @Constant(intValue = 17))
    private int appliedenhancements$shiftRowsDown(int original) {
        return appliedenhancements$isSupportedScreen()
                ? appliedenhancements$EXPANDED_HEADER_HEIGHT
                : original;
    }

    @ModifyArg(
            method = "drawFG",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/client/gui/me/patternaccess/PatternSlot;<init>(Lappeng/client/gui/me/patternaccess/PatternContainerRecord;III)V"),
            index = 3)
    private int appliedenhancements$shiftPatternSlotDown(int originalY) {
        return appliedenhancements$isSupportedScreen()
                ? originalY + appliedenhancements$TOOLBAR_ROW_HEIGHT
                : originalY;
    }

    @Inject(method = "getHoveredLineIndex", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$useExpandedHeaderForRowHover(
            int mouseX,
            int mouseY,
            CallbackInfoReturnable<Integer> callback) {
        if (!appliedenhancements$isSupportedScreen()) {
            return;
        }
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        int relativeX = mouseX - screen.getGuiLeft() - 8;
        int relativeY = mouseY - screen.getGuiTop()
                - 2 * appliedenhancements$TOOLBAR_ROW_HEIGHT;
        if (relativeX < 0 || relativeY < 0
                || relativeX >= 9 * 18
                || relativeY >= visibleRows * 18) {
            callback.setReturnValue(-1);
            return;
        }
        int rowIndex = scrollbar.getCurrentScroll() + relativeY / 18;
        callback.setReturnValue(rowIndex >= 0 && rowIndex < rows.size()
                ? rowIndex
                : -1);
    }

    @Inject(method = { "init", "m_7856_" }, at = @At("RETURN"))
    private void appliedenhancements$addDuplicateButton(CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen()) {
            return;
        }
        appliedenhancements$ensureQuickMoveState();
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        if (appliedenhancements$duplicateButton == null) {
            appliedenhancements$duplicateButton = new DuplicatePatternToggleButton(selected -> {
                if (selected && appliedenhancements$quickMove.enabled()) {
                    appliedenhancements$quickMove.setEnabled(false);
                    appliedenhancements$contextMenu.close();
                    appliedenhancements$updateQuickMoveButton();
                }
                if (selected && appliedenhancements$invalidOnly) {
                    appliedenhancements$invalidOnly = false;
                    appliedenhancements$invalidButton.setSelected(false);
                }
                appliedenhancements$duplicatesOnly = selected;
                appliedenhancements$refreshList();
            });
        }
        appliedenhancements$duplicateButton.setSelected(appliedenhancements$duplicatesOnly);
        appliedenhancements$duplicateButton.setPosition(
                screen.getGuiLeft() + 8,
                screen.getGuiTop() + 21);
        ((ScreenWidgetBridge) this).appliedenhancements$addRenderableWidget(
                appliedenhancements$duplicateButton);

        if (appliedenhancements$invalidButton == null) {
            appliedenhancements$invalidButton = new InvalidPatternToggleButton(selected -> {
                if (selected && appliedenhancements$quickMove.enabled()) {
                    appliedenhancements$quickMove.setEnabled(false);
                    appliedenhancements$contextMenu.close();
                    appliedenhancements$updateQuickMoveButton();
                }
                if (selected && appliedenhancements$duplicatesOnly) {
                    appliedenhancements$duplicatesOnly = false;
                    appliedenhancements$duplicateButton.setSelected(false);
                }
                appliedenhancements$invalidOnly = selected;
                appliedenhancements$refreshList();
            });
        }
        appliedenhancements$invalidButton.setSelected(appliedenhancements$invalidOnly);
        appliedenhancements$invalidButton.setPosition(
                screen.getGuiLeft() + 32,
                screen.getGuiTop() + 21);
        ((ScreenWidgetBridge) this).appliedenhancements$addRenderableWidget(
                appliedenhancements$invalidButton);

        if (appliedenhancements$quickMoveButton == null) {
            appliedenhancements$quickMoveButton = new QuickPatternMoveToggleButton(enabled -> {
                if (enabled && appliedenhancements$isFilterActive()) {
                    appliedenhancements$duplicatesOnly = false;
                    appliedenhancements$invalidOnly = false;
                    appliedenhancements$duplicateButton.setSelected(false);
                    appliedenhancements$invalidButton.setSelected(false);
                    appliedenhancements$refreshList();
                }
                appliedenhancements$quickMove.setEnabled(enabled);
                if (!enabled) {
                    appliedenhancements$contextMenu.close();
                }
                appliedenhancements$updateQuickMoveButton();
            });
        }
        appliedenhancements$quickMoveButton.setSelected(appliedenhancements$quickMove.enabled());
        appliedenhancements$quickMoveButton.setPosition(
                screen.getGuiLeft() + 56,
                screen.getGuiTop() + 21);
        ((ScreenWidgetBridge) this).appliedenhancements$addRenderableWidget(
                appliedenhancements$quickMoveButton);

        scrollbar.setPosition(new Point(
                screen.getGuiLeft() + 175,
                screen.getGuiTop() + 36));
    }

    @Inject(method = "refreshList", at = @At("RETURN"))
    private void appliedenhancements$filterDuplicateRows(CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$isFilterActive()) {
            appliedenhancements$displayToSource = Map.of();
            appliedenhancements$displaySources = Map.of();
            return;
        }

        var level = Minecraft.getInstance().level;
        String filter = searchField.getValue().toLowerCase(Locale.ROOT);
        if (appliedenhancements$rowFactory == null) {
            appliedenhancements$rowFactory = PatternTerminalRowFactory.create(
                    "appeng.client.gui.me.patternaccess.PatternAccessTermScreen");
        }

        DuplicatePatternRows.BuildResult result;
        if (appliedenhancements$invalidOnly) {
            result = DuplicatePatternRows.buildInvalid(
                    byId.values(),
                    level,
                    (InvalidSourcePattern source) -> filter.isEmpty()
                            || source.container().getSearchName().contains(filter)
                            || source.container().getInventory().getStackInSlot(
                                    source.slot().slot()).getHoverName().getString()
                                    .toLowerCase(Locale.ROOT).contains(filter),
                    appliedenhancements$rowFactory);
        } else {
            result = DuplicatePatternRows.build(
                    byId.values(),
                    level,
                    source -> filter.isEmpty()
                            || source.container().getSearchName().contains(filter)
                            || DuplicatePatternIndex.outputMatchesSearch(
                                    source.container().getInventory().getStackInSlot(
                                            source.slot().slot()),
                                    level,
                                    filter),
                    appliedenhancements$rowFactory);
        }
        rows.clear();
        rows.addAll(result.rows());
        appliedenhancements$displayToSource = result.displayToSource();
        appliedenhancements$displaySources = result.displaySources();
        appliedenhancements$resetScrollbar();
    }

    @Inject(method = "postIncrementalUpdate", at = @At("RETURN"))
    private void appliedenhancements$refreshAfterIncrementalUpdate(
            long inventoryId,
            Int2ObjectMap<ItemStack> slots,
            CallbackInfo callback) {
        if (appliedenhancements$isSupportedScreen() && appliedenhancements$isFilterActive()) {
            appliedenhancements$refreshList();
        }
    }

    @Inject(method = "drawBG", at = @At("RETURN"))
    private void appliedenhancements$drawSecondToolbarRow(
            GuiGraphics graphics,
            int offsetX,
            int offsetY,
            int mouseX,
            int mouseY,
            float partialTicks,
            CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen()) {
            return;
        }
        for (int y = appliedenhancements$ORIGINAL_HEADER_HEIGHT - 1;
                y < appliedenhancements$EXPANDED_HEADER_HEIGHT;
                y++) {
            graphics.blit(
                    appliedenhancements$PATTERN_TERMINAL_TEXTURE,
                    offsetX,
                    offsetY + y,
                    0,
                    appliedenhancements$HEADER_INTERIOR_SAMPLE_Y,
                    195,
                    1);
        }
    }

    @Inject(method = "drawBG", at = @At("RETURN"))
    private void appliedenhancements$drawDuplicateBackground(
            GuiGraphics graphics,
            int offsetX,
            int offsetY,
            int mouseX,
            int mouseY,
            float partialTicks,
            CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$isFilterActive()) {
            return;
        }
        int scroll = scrollbar.getCurrentScroll();
        for (int rowIndex = 0; rowIndex < visibleRows; rowIndex++) {
            int modelIndex = scroll + rowIndex;
            if (modelIndex >= rows.size()) {
                break;
            }
            if (rows.get(modelIndex) instanceof PatternTerminalSlotsRowBridge slotsRow) {
                for (int column = 0;
                        column < slotsRow.appliedenhancements$getSlotCount(); column++) {
                    int x = offsetX + 8 + column * 18;
                    int y = offsetY + (rowIndex + 2) * 18;
                    graphics.fill(
                            x, y, x + 16, y + 16,
                            appliedenhancements$invalidOnly ? 0x55D94B4B : 0x553FA9F5);
                }
            }
        }
    }

    @Inject(method = { "slotClicked", "m_6597_" }, at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$redirectSortedSlotClick(
            Slot slot,
            int slotIndex,
            int mouseButton,
            ClickType clickType,
            CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$isFilterActive()) {
            return;
        }
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        var player = Minecraft.getInstance().player;
        if (player != null && DuplicatePatternInteraction.handleClick(
                (PatternAccessTermMenu) screen.getMenu(),
                player,
                appliedenhancements$displayToSource,
                slot,
                mouseButton,
                clickType)) {
            callback.cancel();
        }
    }

    @Inject(method = "drawFG", at = @At("RETURN"))
    private void appliedenhancements$drawQuickMoveState(
            GuiGraphics graphics,
            int offsetX,
            int offsetY,
            int mouseX,
            int mouseY,
            CallbackInfo callback) {
        appliedenhancements$ensureQuickMoveState();
        if (!appliedenhancements$isSupportedScreen()) {
            appliedenhancements$headerHits = List.of();
            return;
        }
        if (appliedenhancements$isFilterActive()) {
            var screen = (PatternAccessTermScreen<?>) (Object) this;
            DuplicatePatternSourceDisplay.renderBadges(
                    graphics,
                    screen.getMenu().slots,
                    appliedenhancements$displaySources);
        }
        if (!appliedenhancements$quickMove.enabled()) {
            appliedenhancements$headerHits = List.of();
            return;
        }
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        appliedenhancements$quickMove.renderSelectedSlots(
                graphics, screen.getMenu().slots, appliedenhancements$displayToSource);
        appliedenhancements$updateQuickMoveButton();

        if (appliedenhancements$isFilterActive()) {
            appliedenhancements$headerHits = List.of();
            return;
        }
        var hits = new ArrayList<Hit>();
        int scroll = scrollbar.getCurrentScroll();
        for (int rowIndex = 0; rowIndex < visibleRows; rowIndex++) {
            int modelIndex = scroll + rowIndex;
            if (modelIndex >= rows.size()) {
                break;
            }
            if (rows.get(modelIndex) instanceof PatternTerminalGroupHeaderBridge header) {
                int rowTop = appliedenhancements$EXPANDED_HEADER_HEIGHT + rowIndex * 18;
                var groupContainers = appliedenhancements$getGroupContainers(
                        header.appliedenhancements$getGroup());
                int actionX = PatternHeaderActionButton.actionStartX(
                        header.appliedenhancements$getGroup(), groupContainers.size());
                int relativeY = rowTop + 5;
                int absoluteY = screen.getGuiTop() + relativeY;
                Hit cut = new Hit(
                        screen.getGuiLeft() + actionX,
                        absoluteY,
                        Action.CUT,
                        header.appliedenhancements$getGroup());
                Hit paste = new Hit(
                        screen.getGuiLeft() + actionX + 11,
                        absoluteY,
                        Action.PASTE,
                        header.appliedenhancements$getGroup());
                hits.add(cut);
                hits.add(paste);
                PatternHeaderActionButton.render(
                        graphics, actionX, relativeY, Action.CUT,
                        cut.contains(mouseX, mouseY), true);
                PatternHeaderActionButton.render(
                        graphics, actionX + 11, relativeY, Action.PASTE,
                        paste.contains(mouseX, mouseY),
                        appliedenhancements$quickMove.hasCutBuffer());
            }
        }
        appliedenhancements$headerHits = List.copyOf(hits);
    }

    @Override
    public boolean appliedenhancements$quickMoveMouseClicked(
            double mouseX, double mouseY, int button) {
        appliedenhancements$ensureQuickMoveState();
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$quickMove.enabled()) {
            return false;
        }
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        if (appliedenhancements$contextMenu.isOpen()) {
            return appliedenhancements$contextMenu.mouseClicked(mouseX, mouseY, button);
        }
        for (Hit hit : appliedenhancements$headerHits) {
            if (hit.contains(mouseX, mouseY) && button == 0) {
                var containers = appliedenhancements$getGroupContainers(hit.group());
                if (hit.action() == Action.CUT) {
                    appliedenhancements$quickMove.cutGroup(containers);
                    appliedenhancements$showQuickMoveMessage(
                            "message.appliedenhancements.pattern_quick_move.cut",
                            appliedenhancements$quickMove.cutCount());
                } else {
                    appliedenhancements$pasteToContainers(containers, -1);
                }
                appliedenhancements$updateQuickMoveButton();
                return true;
            }
        }

        if (ItemContextMenuKeyMapping.matchesPatternMouse(button)
                && appliedenhancements$openQuickMoveContextMenu(mouseX, mouseY)) {
            return true;
        }

        if (ItemContextMenuKeyMapping.matchesPatternMouse(button)
                && mouseX >= screen.getGuiLeft() + 8
                && mouseX < screen.getGuiLeft() + 170
                && mouseY >= screen.getGuiTop() + appliedenhancements$EXPANDED_HEADER_HEIGHT
                && mouseY < screen.getGuiTop()
                        + appliedenhancements$EXPANDED_HEADER_HEIGHT + visibleRows * 18) {
            return true;
        }

        return button == 0 && appliedenhancements$quickMove.beginSelection(
                mouseX,
                mouseY,
                screen.getGuiLeft() + 8,
                screen.getGuiTop() + appliedenhancements$EXPANDED_HEADER_HEIGHT,
                screen.getGuiLeft() + 170,
                screen.getGuiTop()
                        + appliedenhancements$EXPANDED_HEADER_HEIGHT + visibleRows * 18,
                false);
    }

    @Override
    public boolean appliedenhancements$openQuickMoveContextMenu(
            double mouseX, double mouseY) {
        appliedenhancements$ensureQuickMoveState();
        if (!appliedenhancements$isSupportedScreen()
                || !appliedenhancements$quickMove.enabled()) {
            return false;
        }
        PatternSlot hovered = appliedenhancements$findPatternSlot(mouseX, mouseY);
        if (hovered == null) {
            return false;
        }
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        var entries = new ArrayList<Entry>();
        if (!hovered.getItem().isEmpty()
                && appliedenhancements$quickMove.isSelected(
                        hovered, appliedenhancements$displayToSource)) {
            entries.add(new Entry(
                    Component.translatable(
                            "gui.appliedenhancements.pattern_quick_move.cut"),
                    () -> {
                        appliedenhancements$quickMove.cutSelectedPattern(
                                hovered, appliedenhancements$displayToSource);
                        appliedenhancements$showQuickMoveMessage(
                                "message.appliedenhancements.pattern_quick_move.cut",
                                appliedenhancements$quickMove.cutCount());
                        appliedenhancements$updateQuickMoveButton();
                    }));
        }
        if (hovered.getItem().isEmpty()
                && appliedenhancements$quickMove.hasCutBuffer()) {
            entries.add(new Entry(
                    Component.translatable(
                            "gui.appliedenhancements.pattern_quick_move.paste"),
                    () -> {
                        appliedenhancements$quickMove.paste(
                                screen.getMenu().containerId,
                                List.of(hovered.getMachineInv().getServerId()),
                                hovered.getContainerSlot());
                        appliedenhancements$updateQuickMoveButton();
                    }));
        }
        if (!entries.isEmpty()) {
            appliedenhancements$contextMenu.open(
                    (int) mouseX, (int) mouseY,
                    screen.width, screen.height, entries);
        }
        // Quick Move owns every pattern-slot trigger, even with no valid action.
        return true;
    }

    @Override
    public boolean appliedenhancements$quickMoveMouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        appliedenhancements$ensureQuickMoveState();
        return button == 0 && appliedenhancements$quickMove.drag(mouseX, mouseY);
    }

    @Override
    public boolean appliedenhancements$quickMoveMouseReleased(
            double mouseX, double mouseY, int button) {
        appliedenhancements$ensureQuickMoveState();
        if (button != 0 || !appliedenhancements$isSupportedScreen()) {
            return false;
        }
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        boolean handled = appliedenhancements$quickMove.finishSelection(
                mouseX,
                mouseY,
                screen.getGuiLeft(),
                screen.getGuiTop(),
                screen.getMenu().slots,
                appliedenhancements$displayToSource);
        if (handled) {
            appliedenhancements$updateQuickMoveButton();
        }
        return handled;
    }

    @Override
    public void appliedenhancements$renderQuickMoveOverlay(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        appliedenhancements$ensureQuickMoveState();
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$quickMove.enabled()) {
            return;
        }
        appliedenhancements$quickMove.renderSelectionBox(graphics);
        appliedenhancements$contextMenu.render(graphics, mouseX, mouseY);
        if (appliedenhancements$contextMenu.isOpen()) {
            return;
        }
        for (Hit hit : appliedenhancements$headerHits) {
            if (hit.contains(mouseX, mouseY)) {
                graphics.renderTooltip(
                        Minecraft.getInstance().font,
                        Component.translatable(hit.action() == Action.CUT
                                ? "gui.appliedenhancements.pattern_quick_move.cut_all"
                                : "gui.appliedenhancements.pattern_quick_move.paste_all"),
                        mouseX,
                        mouseY);
                return;
            }
        }
        PatternSlot hovered = appliedenhancements$findPatternSlot(mouseX, mouseY);
        if (hovered != null) {
            Component action = null;
            if (!hovered.getItem().isEmpty()
                    && appliedenhancements$quickMove.isSelected(
                            hovered, appliedenhancements$displayToSource)) {
                action = Component.translatable(
                        "gui.appliedenhancements.pattern_quick_move.cut");
            } else if (hovered.getItem().isEmpty()
                    && appliedenhancements$quickMove.hasCutBuffer()) {
                action = Component.translatable(
                        "gui.appliedenhancements.pattern_quick_move.paste");
            }
            if (action != null) {
                graphics.renderTooltip(Minecraft.getInstance().font, action, mouseX, mouseY);
            }
        }
    }

    @Unique
    private PatternSlot appliedenhancements$findPatternSlot(double mouseX, double mouseY) {
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        for (Slot slot : screen.getMenu().slots) {
            if (slot instanceof PatternSlot patternSlot
                    && mouseX >= screen.getGuiLeft() + patternSlot.x
                    && mouseX < screen.getGuiLeft() + patternSlot.x + 16
                    && mouseY >= screen.getGuiTop() + patternSlot.y
                    && mouseY < screen.getGuiTop() + patternSlot.y + 16) {
                return patternSlot;
            }
        }
        return null;
    }

    @Override
    public List<Component> appliedenhancements$getPatternSourceTooltip(PatternSlot slot) {
        if (!appliedenhancements$isFilterActive()) {
            return List.of();
        }
        PatternSource source = appliedenhancements$displaySources.get(new PatternSlotRef(
                slot.getMachineInv().getServerId(),
                slot.getContainerSlot()));
        return source == null ? List.of() : source.tooltip();
    }

    @Unique
    private List<PatternContainerRecord> appliedenhancements$getGroupContainers(
            appeng.api.implementations.blockentities.PatternContainerGroup group) {
        return byId.values().stream()
                .filter(container -> container.getGroup().equals(group))
                .toList();
    }

    @Unique
    private void appliedenhancements$pasteToContainers(
            List<PatternContainerRecord> containers, int preferredSlot) {
        if (containers.isEmpty()) {
            return;
        }
        var screen = (PatternAccessTermScreen<?>) (Object) this;
        if (appliedenhancements$quickMove.paste(
                screen.getMenu().containerId,
                containers.stream().map(PatternContainerRecord::getServerId).toList(),
                preferredSlot)) {
            appliedenhancements$updateQuickMoveButton();
        }
    }

    @Unique
    private void appliedenhancements$showQuickMoveMessage(String key, int count) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, count), true);
        }
    }

    @Unique
    private void appliedenhancements$updateQuickMoveButton() {
        appliedenhancements$ensureQuickMoveState();
        if (appliedenhancements$quickMoveButton != null) {
            appliedenhancements$quickMoveButton.setSelected(
                    appliedenhancements$quickMove.enabled());
            appliedenhancements$quickMoveButton.setCounts(
                    appliedenhancements$quickMove.selectedCount(),
                    appliedenhancements$quickMove.cutCount());
        }
    }

    @Unique
    private void appliedenhancements$ensureQuickMoveState() {
        if (appliedenhancements$quickMove == null) {
            appliedenhancements$quickMove = new PatternQuickMoveSession();
        }
        if (appliedenhancements$displayToSource == null) {
            appliedenhancements$displayToSource = Map.of();
        }
        if (appliedenhancements$displaySources == null) {
            appliedenhancements$displaySources = Map.of();
        }
        if (appliedenhancements$headerHits == null) {
            appliedenhancements$headerHits = List.of();
        }
        if (appliedenhancements$contextMenu == null) {
            appliedenhancements$contextMenu = new PatternQuickMoveContextMenu();
        }
    }

    @Unique
    private boolean appliedenhancements$isSupportedScreen() {
        return PatternTerminalIntegrationApi.supports(
                this, Family.AE2_PATTERN_ACCESS);
    }

    @Unique
    private boolean appliedenhancements$isFilterActive() {
        return appliedenhancements$duplicatesOnly || appliedenhancements$invalidOnly;
    }
}
