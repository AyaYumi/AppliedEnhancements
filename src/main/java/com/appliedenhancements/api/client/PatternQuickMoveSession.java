package com.appliedenhancements.api.client;

import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import appeng.client.gui.me.patternaccess.PatternSlot;
import com.appliedenhancements.api.PatternSlotRef;
import com.appliedenhancements.client.pattern.PatternQuickMoveController;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;

/**
 * Client-only selection and cut-buffer session for a pattern terminal screen.
 *
 * <p>Create one instance per opened screen and call {@link #clear()} when the
 * screen closes. Paste operations are sent through the server-authoritative
 * {@code PatternBatchMoveApi}; this object never changes a server inventory
 * directly.</p>
 */
public final class PatternQuickMoveSession {
    private final PatternQuickMoveController delegate = new PatternQuickMoveController();

    public boolean enabled() {
        return delegate.enabled();
    }

    public void setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
    }

    public int selectedCount() {
        return delegate.selectedCount();
    }

    public int cutCount() {
        return delegate.cutCount();
    }

    public boolean beginSelection(
            double mouseX,
            double mouseY,
            int left,
            int top,
            int right,
            int bottom,
            boolean subtract) {
        return delegate.beginSelection(
                mouseX, mouseY, left, top, right, bottom, subtract);
    }

    public boolean drag(double mouseX, double mouseY) {
        return delegate.drag(mouseX, mouseY);
    }

    public boolean finishSelection(
            double mouseX,
            double mouseY,
            int guiLeft,
            int guiTop,
            Collection<Slot> menuSlots,
            Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        return delegate.finishSelection(
                mouseX, mouseY, guiLeft, guiTop, menuSlots, displayToSource);
    }

    public void renderSelectionBox(GuiGraphics graphics) {
        delegate.renderSelectionBox(graphics);
    }

    public void renderSelectedSlots(
            GuiGraphics graphics,
            Collection<Slot> menuSlots,
            Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        delegate.renderSelectedSlots(graphics, menuSlots, displayToSource);
    }

    public boolean cutSelectedPattern(
            PatternSlot slot,
            Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        return delegate.cutSelectedPattern(slot, displayToSource);
    }

    public int cutGroup(Collection<PatternContainerRecord> containers) {
        return delegate.cutGroup(containers);
    }

    public boolean paste(
            int menuId, List<Long> targetContainerIds, int preferredSlot) {
        return delegate.paste(menuId, targetContainerIds, preferredSlot);
    }

    public boolean isSelected(
            PatternSlot slot,
            Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        return delegate.isSelected(slot, displayToSource);
    }

    public boolean hasCutBuffer() {
        return delegate.hasCutBuffer();
    }

    public void clear() {
        delegate.clear();
    }
}
