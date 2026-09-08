package com.appliedenhancements.client.pattern;

import appeng.client.gui.me.patternaccess.PatternSlot;
import appeng.core.sync.packets.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.implementations.PatternAccessTermMenu;
import com.appliedenhancements.api.PatternSlotRef;
import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import appeng.core.sync.BasePacket;
import com.appliedenhancements.network.NetworkHandler;

/** Redirects clicks on compact filtered rows back to their original server slots. */
public final class DuplicatePatternInteraction {
    private DuplicatePatternInteraction() {
    }

    public static boolean handleClick(
            PatternAccessTermMenu menu,
            Player player,
            Map<PatternSlotRef, PatternSlotRef> displayToSource,
            Slot slot,
            int mouseButton,
            ClickType clickType) {
        if (slot instanceof PatternSlot patternSlot) {
            PatternSlotRef source = displayToSource.get(new PatternSlotRef(
                    patternSlot.getMachineInv().getServerId(),
                    patternSlot.getContainerSlot()));
            if (source == null) {
                return false;
            }

            InventoryAction action = switch (clickType) {
                case PICKUP -> mouseButton == 1
                        ? InventoryAction.SPLIT_OR_PLACE_SINGLE
                        : InventoryAction.PICKUP_OR_SET_DOWN;
                case QUICK_MOVE -> mouseButton == 1
                        ? InventoryAction.PICKUP_SINGLE
                        : InventoryAction.SHIFT_CLICK;
                case CLONE -> player.getAbilities().instabuild
                        ? InventoryAction.CREATIVE_DUPLICATE
                        : null;
                default -> null;
            };
            if (action != null) {
                appeng.core.sync.network.NetworkHandler.instance().sendToServer(new InventoryActionPacket(
                        action, source.slot(), source.containerId()));
            }
            return true;
        }

        if (clickType == ClickType.QUICK_MOVE
                && slot != null
                && slot.container == player.getInventory()) {
            var visibleContainers = new LinkedHashSet<Long>();
            for (PatternSlotRef source : displayToSource.values()) {
                visibleContainers.add(source.containerId());
            }
            return sendFilteredQuickMove(
                    menu.containerId,
                    slot.getContainerSlot(),
                    List.copyOf(visibleContainers));
        }
        return false;
    }

    /**
     * AE2 added QuickMovePatternPacket after the first 19.2 releases. Use it
     * when present; older AE2 versions fall through to their native shift-click
     * behavior instead of failing class loading for the whole screen.
     */
    private static boolean sendFilteredQuickMove(
            int menuId, int clickedSlot, List<Long> visibleContainers) {
        try {
            Class<?> packetClass = Class.forName(
                    "appeng.core.sync.packets.QuickMovePatternPacket",
                    false,
                    DuplicatePatternInteraction.class.getClassLoader());
            Constructor<?> constructor = packetClass.getConstructor(
                    int.class, int.class, List.class);
            Object packet = constructor.newInstance(
                    menuId, clickedSlot, visibleContainers);
            if (packet instanceof BasePacket payload) {
                appeng.core.sync.network.NetworkHandler.instance().sendToServer(payload);
                return true;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // AE2 19.2.x before QuickMovePatternPacket: let the screen use its
            // ordinary player-inventory quick-move path.
        }
        return false;
    }
}
