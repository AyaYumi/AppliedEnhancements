package com.appliedenhancements.mixin;

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
import com.appliedenhancements.client.pattern.DuplicatePatternToggleButton;
import com.appliedenhancements.client.pattern.PatternHeaderActionButton;
import com.appliedenhancements.client.pattern.PatternHeaderActionButton.Action;
import com.appliedenhancements.client.pattern.PatternHeaderActionButton.Hit;
import com.appliedenhancements.client.pattern.PatternQuickMoveContextMenu;
import com.appliedenhancements.client.pattern.PatternQuickMoveContextMenu.Entry;
import com.appliedenhancements.client.pattern.PatternTerminalRowFactory;
import com.appliedenhancements.client.menu.ItemContextMenuKeyMapping;
import com.appliedenhancements.client.pattern.QuickPatternMoveToggleButton;
import com.appliedenhancements.integration.ae2.PatternQuickMoveScreenBridge;
import com.appliedenhancements.integration.ae2.PatternTerminalGroupHeaderBridge;
import com.appliedenhancements.integration.ae2.PatternTerminalSlotsRowBridge;
import com.appliedenhancements.integration.ae2.ScreenWidgetBridge;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Duplicate-output filter for ExtendedAE's wired and wireless extended terminals. */
@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal", remap = false)
public abstract class WirelessExtendedPatternAccessDuplicateMixin
        implements PatternQuickMoveScreenBridge {
    @Unique
    private static final String appliedenhancements$WIRED_SCREEN =
            "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal";
    @Unique
    private static final String appliedenhancements$WIRELESS_SCREEN =
            "com.glodblock.github.extendedae.xmod.wt.GuiWirelessExPAT";

    @Shadow
    @Final
    private HashMap<Long, PatternContainerRecord> byId;

    @Shadow
    @Final
    private HashMap<Integer, Object> highlightBtns;

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
    protected int visibleRows;

    @Shadow
    @Final
    private Set<ItemStack> matchedStack;

    @Shadow
    @Final
    private Set<PatternContainerRecord> matchedProvider;

    @Unique
    private boolean appliedenhancements$duplicatesOnly;
    @Unique
    private Map<PatternSlotRef, PatternSlotRef> appliedenhancements$displayToSource = Map.of();
    @Unique
    private DuplicatePatternToggleButton appliedenhancements$duplicateButton;
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

    @Inject(method = "init", at = @At("RETURN"))
    private void appliedenhancements$addDuplicateButton(CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen()) {
            return;
        }
        appliedenhancements$ensureQuickMoveState();
        var screen = (AbstractContainerScreen<?>) (Object) this;
        if (appliedenhancements$duplicateButton == null) {
            appliedenhancements$duplicateButton = new DuplicatePatternToggleButton(selected -> {
                if (selected && appliedenhancements$quickMove.enabled()) {
                    appliedenhancements$quickMove.setEnabled(false);
                    appliedenhancements$contextMenu.close();
                    appliedenhancements$updateQuickMoveButton();
                }
                appliedenhancements$duplicatesOnly = selected;
                appliedenhancements$refreshList();
            });
        }
        appliedenhancements$duplicateButton.setSelected(appliedenhancements$duplicatesOnly);
        appliedenhancements$duplicateButton.setPosition(
                screen.getGuiLeft() + 87,
                screen.getGuiTop() + 17);
        ((ScreenWidgetBridge) this).appliedenhancements$addRenderableWidget(
                appliedenhancements$duplicateButton);

        if (appliedenhancements$quickMoveButton == null) {
            appliedenhancements$quickMoveButton = new QuickPatternMoveToggleButton(enabled -> {
                if (enabled && appliedenhancements$duplicatesOnly) {
                    appliedenhancements$duplicatesOnly = false;
                    appliedenhancements$duplicateButton.setSelected(false);
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
                screen.getGuiLeft() + 111,
                screen.getGuiTop() + 17);
        ((ScreenWidgetBridge) this).appliedenhancements$addRenderableWidget(
                appliedenhancements$quickMoveButton);
    }

    @Inject(method = "refreshList", at = @At("RETURN"))
    private void appliedenhancements$filterDuplicateRows(CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$duplicatesOnly) {
            appliedenhancements$displayToSource = Map.of();
            return;
        }

        var level = Minecraft.getInstance().level;
        boolean emptySearch = searchField.getValue().trim().isEmpty();
        if (appliedenhancements$rowFactory == null) {
            appliedenhancements$rowFactory = PatternTerminalRowFactory.create(
                    "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal");
        }

        var result = DuplicatePatternRows.build(
                byId.values(),
                level,
                source -> emptySearch
                        || matchedProvider.contains(source.container())
                        || matchedStack.contains(source.container().getInventory()
                                .getStackInSlot(source.slot().slot())),
                appliedenhancements$rowFactory);
        var screenBridge = (ScreenWidgetBridge) this;
        for (Object button : highlightBtns.values()) {
            if (button instanceof GuiEventListener widget) {
                screenBridge.appliedenhancements$removeWidget(widget);
            }
        }
        highlightBtns.clear();
        rows.clear();
        rows.addAll(result.rows());
        appliedenhancements$displayToSource = result.displayToSource();
        appliedenhancements$resetScrollbar();
    }

    @Inject(method = "postIncrementalUpdate", at = @At("RETURN"))
    private void appliedenhancements$refreshAfterIncrementalUpdate(
            long inventoryId,
            Int2ObjectMap<ItemStack> slots,
            CallbackInfo callback) {
        if (appliedenhancements$isSupportedScreen() && appliedenhancements$duplicatesOnly) {
            appliedenhancements$refreshList();
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
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$duplicatesOnly) {
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
                    int y = offsetY + (rowIndex + 1) * 18 + 13;
                    graphics.fill(x, y, x + 16, y + 16, 0x553FA9F5);
                }
            }
        }
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$redirectSortedSlotClick(
            Slot slot,
            int slotIndex,
            int mouseButton,
            ClickType clickType,
            CallbackInfo callback) {
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$duplicatesOnly) {
            return;
        }
        var screen = (AbstractContainerScreen<?>) (Object) this;
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
        if (!appliedenhancements$isSupportedScreen() || !appliedenhancements$quickMove.enabled()) {
            appliedenhancements$headerHits = List.of();
            return;
        }
        var screen = (AbstractContainerScreen<?>) (Object) this;
        appliedenhancements$quickMove.renderSelectedSlots(
                graphics, screen.getMenu().slots, appliedenhancements$displayToSource);
        appliedenhancements$updateQuickMoveButton();

        if (appliedenhancements$duplicatesOnly) {
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
                int rowTop = 30 + rowIndex * 18;
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
        var screen = (AbstractContainerScreen<?>) (Object) this;
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

        if (ItemContextMenuKeyMapping.matchesMouse(button)
                && appliedenhancements$openQuickMoveContextMenu(mouseX, mouseY)) {
            return true;
        }

        if (ItemContextMenuKeyMapping.matchesMouse(button)
                && mouseX >= screen.getGuiLeft() + 8
                && mouseX < screen.getGuiLeft() + 170
                && mouseY >= screen.getGuiTop() + 30
                && mouseY < screen.getGuiTop() + 30 + visibleRows * 18) {
            return true;
        }

        return button == 0 && appliedenhancements$quickMove.beginSelection(
                mouseX,
                mouseY,
                screen.getGuiLeft() + 8,
                screen.getGuiTop() + 30,
                screen.getGuiLeft() + 170,
                screen.getGuiTop() + 30 + visibleRows * 18,
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
        var screen = (AbstractContainerScreen<?>) (Object) this;
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
        var screen = (AbstractContainerScreen<?>) (Object) this;
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
        var screen = (AbstractContainerScreen<?>) (Object) this;
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
        var screen = (AbstractContainerScreen<?>) (Object) this;
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
                this, Family.EXTENDEDAE_PATTERN_ACCESS);
    }
}
