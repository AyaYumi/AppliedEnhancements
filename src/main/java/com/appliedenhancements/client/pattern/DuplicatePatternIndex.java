package com.appliedenhancements.client.pattern;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import com.appliedenhancements.api.PatternDuplicateApi;
import com.appliedenhancements.api.PatternDuplicateApi.PatternEntry;
import com.appliedenhancements.api.PatternSlotRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Builds a client-side index of patterns that share the same primary output key. */
public final class DuplicatePatternIndex {
    private DuplicatePatternIndex() {
    }

    public static Set<PatternSlotRef> findDuplicateSlots(
            Collection<PatternContainerRecord> containers, Level level) {
        return PatternDuplicateApi.findDuplicateSlots(indexPrimaryOutputs(containers, level));
    }

    public static Map<PatternSlotRef, AEKey> indexPrimaryOutputs(
            Collection<PatternContainerRecord> containers, Level level) {
        var entries = new ArrayList<PatternEntry>();
        for (var container : containers) {
            var inventory = container.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                entries.add(new PatternEntry(
                        new PatternSlotRef(container.getServerId(), slot),
                        inventory.getStackInSlot(slot)));
            }
        }
        return PatternDuplicateApi.indexPrimaryOutputs(entries, level);
    }

    public static boolean outputMatchesSearch(
            ItemStack patternStack, Level level, String lowercaseFilter) {
        return PatternDuplicateApi.outputMatchesSearch(
                patternStack, level, lowercaseFilter);
    }

    static <K> Set<PatternSlotRef> findDuplicates(Map<PatternSlotRef, K> outputs) {
        return PatternDuplicateApi.findDuplicateSlots(outputs);
    }
}
