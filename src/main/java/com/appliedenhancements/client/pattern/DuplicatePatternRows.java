package com.appliedenhancements.client.pattern;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import com.appliedenhancements.api.PatternSlotRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/** Creates compact product-grouped rows while retaining original server slot identities. */
public final class DuplicatePatternRows {
    private static final int COLUMNS = 9;

    private DuplicatePatternRows() {
    }

    public static BuildResult build(
            Collection<PatternContainerRecord> containers,
            Level level,
            Predicate<SourcePattern> visible,
            PatternTerminalRowFactory rowFactory) {
        Map<Long, PatternContainerRecord> containersById = new HashMap<>();
        for (var container : containers) {
            containersById.put(container.getServerId(), container);
        }

        Map<PatternSlotRef, AEKey> outputs = DuplicatePatternIndex.indexPrimaryOutputs(
                containers, level);
        Map<AEKey, ArrayList<SourcePattern>> grouped = new HashMap<>();
        for (var entry : outputs.entrySet()) {
            PatternContainerRecord container = containersById.get(
                    entry.getKey().containerId());
            if (container == null || entry.getKey().slot() >= container.getInventory().size()) {
                continue;
            }
            grouped.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
                    .add(new SourcePattern(entry.getKey(), entry.getValue(), container));
        }

        var duplicateGroups = new ArrayList<Map.Entry<AEKey, ArrayList<SourcePattern>>>();
        for (var entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicateGroups.add(entry);
            }
        }
        duplicateGroups.sort(Comparator
                .comparing((Map.Entry<AEKey, ArrayList<SourcePattern>> entry) ->
                        entry.getKey().getDisplayName().getString().toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.getKey().toString()));

        var rows = new ArrayList<Object>();
        var displayToSource = new LinkedHashMap<PatternSlotRef, PatternSlotRef>();
        long displayContainerId = Long.MIN_VALUE;
        long displayOrder = 0;

        for (var groupEntry : duplicateGroups) {
            var allPatterns = groupEntry.getValue();
            var visiblePatterns = new ArrayList<SourcePattern>();
            for (SourcePattern pattern : allPatterns) {
                if (visible.test(pattern)) {
                    visiblePatterns.add(pattern);
                }
            }
            if (visiblePatterns.isEmpty()) {
                continue;
            }
            visiblePatterns.sort(Comparator
                    .comparing((SourcePattern pattern) ->
                            pattern.container().getSearchName())
                    .thenComparingLong(pattern -> pattern.slot().containerId())
                    .thenComparingInt(pattern -> pattern.slot().slot()));

            AEKey output = groupEntry.getKey();
            String count = visiblePatterns.size() == allPatterns.size()
                    ? Integer.toString(allPatterns.size())
                    : visiblePatterns.size() + "/" + allPatterns.size();
            var displayGroup = new PatternContainerGroup(
                    output instanceof AEItemKey itemKey ? itemKey : null,
                    Component.empty()
                            .append(output.getDisplayName())
                            .append(Component.literal(" (" + count + ")")),
                    List.of(Component.translatable(
                            "gui.appliedenhancements.duplicate_patterns.group",
                            allPatterns.size())));
            var displayContainer = new PatternContainerRecord(
                    displayContainerId++,
                    visiblePatterns.size(),
                    displayOrder++,
                    displayGroup);

            for (int slot = 0; slot < visiblePatterns.size(); slot++) {
                SourcePattern source = visiblePatterns.get(slot);
                displayContainer.getInventory().setItemDirect(
                        slot,
                        source.container().getInventory().getStackInSlot(source.slot().slot()));
                displayToSource.put(
                        new PatternSlotRef(displayContainer.getServerId(), slot),
                        source.slot());
            }

            rows.add(rowFactory.createHeader(displayGroup));
            for (int offset = 0; offset < visiblePatterns.size(); offset += COLUMNS) {
                rows.add(rowFactory.createSlots(
                        displayContainer,
                        offset,
                        Math.min(COLUMNS, visiblePatterns.size() - offset)));
            }
        }

        return new BuildResult(List.copyOf(rows), Map.copyOf(displayToSource));
    }

    public record SourcePattern(
            PatternSlotRef slot, AEKey output, PatternContainerRecord container) {
    }

    public record BuildResult(
            List<Object> rows, Map<PatternSlotRef, PatternSlotRef> displayToSource) {
    }
}
