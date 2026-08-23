package com.appliedenhancements.client.pattern;

import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import appeng.client.gui.me.patternaccess.PatternSlot;
import com.appliedenhancements.api.PatternBatchMoveApi;
import com.appliedenhancements.api.PatternSlotRef;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;

/** Per-screen selection and cut buffer. The object is discarded when the screen closes. */
public final class PatternQuickMoveController {
    private final LinkedHashSet<PatternSlotRef> selected = new LinkedHashSet<>();
    private final LinkedHashSet<PatternSlotRef> cut = new LinkedHashSet<>();
    private boolean enabled;
    private boolean selecting;
    private double startX;
    private double startY;
    private double currentX;
    private double currentY;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clear();
        }
    }

    public int selectedCount() {
        return selected.size();
    }

    public int cutCount() {
        return cut.size();
    }

    public boolean beginSelection(
            double mouseX,
            double mouseY,
            int left,
            int top,
            int right,
            int bottom,
            boolean subtract) {
        if (!enabled || mouseX < left || mouseX >= right
                || mouseY < top || mouseY >= bottom) {
            return false;
        }
        selecting = true;
        startX = currentX = mouseX;
        startY = currentY = mouseY;
        return true;
    }

    public boolean drag(double mouseX, double mouseY) {
        if (!selecting) {
            return false;
        }
        currentX = mouseX;
        currentY = mouseY;
        return true;
    }

    public boolean finishSelection(
            double mouseX,
            double mouseY,
            int guiLeft,
            int guiTop,
            Collection<Slot> menuSlots,
            Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        if (!selecting) {
            return false;
        }
        currentX = mouseX;
        currentY = mouseY;
        boolean dragged = Math.abs(currentX - startX) >= 3
                || Math.abs(currentY - startY) >= 3;
        int minX = (int) Math.floor(Math.min(startX, currentX));
        int minY = (int) Math.floor(Math.min(startY, currentY));
        int maxX = (int) Math.ceil(Math.max(startX, currentX));
        int maxY = (int) Math.ceil(Math.max(startY, currentY));
        if (maxX - minX < 2) {
            maxX = minX + 2;
        }
        if (maxY - minY < 2) {
            maxY = minY + 2;
        }

        if (dragged) {
            selected.clear();
            cut.clear();
        }

        for (Slot slot : menuSlots) {
            if (!(slot instanceof PatternSlot patternSlot) || patternSlot.getItem().isEmpty()) {
                continue;
            }
            int slotX = guiLeft + patternSlot.x;
            int slotY = guiTop + patternSlot.y;
            if (slotX < maxX && slotX + 16 > minX
                    && slotY < maxY && slotY + 16 > minY) {
                PatternSlotRef source = resolve(patternSlot, displayToSource);
                if (!dragged) {
                    if (!selected.remove(source)) {
                        selected.add(source);
                    } else {
                        cut.remove(source);
                    }
                } else {
                    selected.add(source);
                }
            }
        }
        selecting = false;
        return true;
    }

    public void renderSelectionBox(GuiGraphics graphics) {
        if (!selecting) {
            return;
        }
        int minX = (int) Math.floor(Math.min(startX, currentX));
        int minY = (int) Math.floor(Math.min(startY, currentY));
        int maxX = (int) Math.ceil(Math.max(startX, currentX));
        int maxY = (int) Math.ceil(Math.max(startY, currentY));
        graphics.fill(minX, minY, maxX, maxY, 0x334FA9F5);
        graphics.fill(minX, minY, maxX, minY + 1, 0xCC77CCFF);
        graphics.fill(minX, maxY - 1, maxX, maxY, 0xCC77CCFF);
        graphics.fill(minX, minY, minX + 1, maxY, 0xCC77CCFF);
        graphics.fill(maxX - 1, minY, maxX, maxY, 0xCC77CCFF);
    }

    public void renderSelectedSlots(
            GuiGraphics graphics,
            Collection<Slot> menuSlots,
            Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        if (!enabled) {
            return;
        }
        for (Slot slot : menuSlots) {
            if (!(slot instanceof PatternSlot patternSlot) || patternSlot.getItem().isEmpty()) {
                continue;
            }
            PatternSlotRef source = resolve(patternSlot, displayToSource);
            if (cut.contains(source)) {
                graphics.fill(patternSlot.x, patternSlot.y,
                        patternSlot.x + 16, patternSlot.y + 16, 0x66FFAA33);
            } else if (selected.contains(source)) {
                graphics.fill(patternSlot.x, patternSlot.y,
                        patternSlot.x + 16, patternSlot.y + 16, 0x6655AAFF);
            }
        }
    }

    public boolean cutSelectedPattern(
            PatternSlot slot, Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        PatternSlotRef source = resolve(slot, displayToSource);
        if (!selected.contains(source)) {
            return false;
        }
        cut.clear();
        cut.addAll(selected);
        return true;
    }

    public int cutGroup(Collection<PatternContainerRecord> containers) {
        selected.clear();
        cut.clear();
        for (PatternContainerRecord container : containers) {
            var inventory = container.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (!inventory.getStackInSlot(slot).isEmpty()) {
                    var source = new PatternSlotRef(container.getServerId(), slot);
                    selected.add(source);
                    cut.add(source);
                }
            }
        }
        return cut.size();
    }

    public boolean paste(
            int menuId, List<Long> targetContainerIds, int preferredSlot) {
        if (cut.isEmpty() || targetContainerIds.isEmpty()) {
            return false;
        }
        PatternBatchMoveApi.requestMove(
                menuId, cut, targetContainerIds, preferredSlot);
        selected.clear();
        cut.clear();
        return true;
    }

    public boolean isSelected(
            PatternSlot slot, Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        return selected.contains(resolve(slot, displayToSource));
    }

    public boolean hasCutBuffer() {
        return !cut.isEmpty();
    }

    public static PatternSlotRef resolve(
            PatternSlot slot, Map<PatternSlotRef, PatternSlotRef> displayToSource) {
        PatternSlotRef displayed = new PatternSlotRef(
                slot.getMachineInv().getServerId(), slot.getContainerSlot());
        return displayToSource.getOrDefault(displayed, displayed);
    }

    public void clear() {
        selected.clear();
        cut.clear();
        selecting = false;
    }
}
