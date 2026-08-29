package com.appliedenhancements.client.pattern;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import com.appliedenhancements.api.PatternDuplicateApi;
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
import net.minecraft.ChatFormatting;
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
        Map<Long, SourceMachine> sourceMachines = indexSourceMachines(containers);

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
        var displaySources = new LinkedHashMap<PatternSlotRef, PatternSource>();
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
            var tooltip = buildGroupTooltip(allPatterns, sourceMachines);
            var displayGroup = new PatternContainerGroup(
                    output instanceof AEItemKey itemKey ? itemKey : null,
                    Component.empty()
                            .append(output.getDisplayName())
                            .append(Component.literal(" (" + count + ")")),
                    tooltip);
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
                var displaySlot = new PatternSlotRef(displayContainer.getServerId(), slot);
                displayToSource.put(displaySlot, source.slot());
                SourceMachine sourceMachine = sourceMachines.get(source.slot().containerId());
                if (sourceMachine != null) {
                    displaySources.put(
                            displaySlot,
                            new PatternSource(sourceMachine, source.slot().slot()));
                }
            }

            rows.add(rowFactory.createHeader(displayGroup));
            for (int offset = 0; offset < visiblePatterns.size(); offset += COLUMNS) {
                rows.add(rowFactory.createSlots(
                        displayContainer,
                        offset,
                        Math.min(COLUMNS, visiblePatterns.size() - offset)));
            }
        }

        return new BuildResult(
                List.copyOf(rows),
                Map.copyOf(displayToSource),
                Map.copyOf(displaySources));
    }

    /** Creates compact machine-grouped rows containing only invalid patterns. */
    public static BuildResult buildInvalid(
            Collection<PatternContainerRecord> containers,
            Level level,
            Predicate<InvalidSourcePattern> visible,
            PatternTerminalRowFactory rowFactory) {
        Map<Long, SourceMachine> sourceMachines = indexSourceMachines(containers);
        var grouped = new HashMap<PatternContainerGroup, ArrayList<InvalidSourcePattern>>();
        for (PatternContainerRecord container : containers) {
            var inventory = container.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (PatternDuplicateApi.isInvalidPattern(
                        inventory.getStackInSlot(slot), level)) {
                    grouped.computeIfAbsent(container.getGroup(), ignored -> new ArrayList<>())
                            .add(new InvalidSourcePattern(
                                    new PatternSlotRef(container.getServerId(), slot),
                                    container));
                }
            }
        }

        var invalidGroups = new ArrayList<
                Map.Entry<PatternContainerGroup, ArrayList<InvalidSourcePattern>>>(
                        grouped.entrySet());
        invalidGroups.sort(Comparator
                .comparing((Map.Entry<PatternContainerGroup, ArrayList<InvalidSourcePattern>> entry) ->
                        entry.getKey().name().getString().toLowerCase(Locale.ROOT))
                .thenComparing(entry -> String.valueOf(entry.getKey().icon())));

        var rows = new ArrayList<Object>();
        var displayToSource = new LinkedHashMap<PatternSlotRef, PatternSlotRef>();
        var displaySources = new LinkedHashMap<PatternSlotRef, PatternSource>();
        long displayContainerId = Long.MIN_VALUE;
        long displayOrder = 0;

        for (var groupEntry : invalidGroups) {
            var allPatterns = groupEntry.getValue();
            var visiblePatterns = new ArrayList<InvalidSourcePattern>();
            for (InvalidSourcePattern pattern : allPatterns) {
                if (visible.test(pattern)) {
                    visiblePatterns.add(pattern);
                }
            }
            if (visiblePatterns.isEmpty()) {
                continue;
            }
            visiblePatterns.sort(Comparator
                    .comparing((InvalidSourcePattern pattern) ->
                            pattern.container().getSearchName())
                    .thenComparingLong(pattern -> pattern.slot().containerId())
                    .thenComparingInt(pattern -> pattern.slot().slot()));

            PatternContainerGroup sourceGroup = groupEntry.getKey();
            String count = visiblePatterns.size() == allPatterns.size()
                    ? Integer.toString(allPatterns.size())
                    : visiblePatterns.size() + "/" + allPatterns.size();
            var displayGroup = new PatternContainerGroup(
                    sourceGroup.icon(),
                    Component.empty()
                            .append(sourceGroup.name())
                            .append(Component.literal(" (" + count + ")")),
                    buildInvalidGroupTooltip(allPatterns, sourceMachines));
            var displayContainer = new PatternContainerRecord(
                    displayContainerId++,
                    visiblePatterns.size(),
                    displayOrder++,
                    displayGroup);

            for (int slot = 0; slot < visiblePatterns.size(); slot++) {
                InvalidSourcePattern source = visiblePatterns.get(slot);
                displayContainer.getInventory().setItemDirect(
                        slot,
                        source.container().getInventory().getStackInSlot(source.slot().slot()));
                var displaySlot = new PatternSlotRef(displayContainer.getServerId(), slot);
                displayToSource.put(displaySlot, source.slot());
                SourceMachine sourceMachine = sourceMachines.get(source.slot().containerId());
                if (sourceMachine != null) {
                    displaySources.put(
                            displaySlot,
                            new PatternSource(sourceMachine, source.slot().slot()));
                }
            }

            rows.add(rowFactory.createHeader(displayGroup));
            for (int offset = 0; offset < visiblePatterns.size(); offset += COLUMNS) {
                rows.add(rowFactory.createSlots(
                        displayContainer,
                        offset,
                        Math.min(COLUMNS, visiblePatterns.size() - offset)));
            }
        }

        return new BuildResult(
                List.copyOf(rows),
                Map.copyOf(displayToSource),
                Map.copyOf(displaySources));
    }

    private static Map<Long, SourceMachine> indexSourceMachines(
            Collection<PatternContainerRecord> containers) {
        var ordered = new ArrayList<>(containers);
        ordered.sort(Comparator
                .<PatternContainerRecord>naturalOrder()
                .thenComparingLong(PatternContainerRecord::getServerId));

        var byGroup = new LinkedHashMap<PatternContainerGroup, ArrayList<PatternContainerRecord>>();
        for (PatternContainerRecord container : ordered) {
            byGroup.computeIfAbsent(container.getGroup(), ignored -> new ArrayList<>())
                    .add(container);
        }

        var result = new HashMap<Long, SourceMachine>();
        for (var entry : byGroup.entrySet()) {
            var groupContainers = entry.getValue();
            for (int index = 0; index < groupContainers.size(); index++) {
                PatternContainerRecord container = groupContainers.get(index);
                result.put(
                        container.getServerId(),
                        new SourceMachine(entry.getKey(), index + 1, groupContainers.size()));
            }
        }
        return Map.copyOf(result);
    }

    private static List<Component> buildGroupTooltip(
            List<SourcePattern> patterns,
            Map<Long, SourceMachine> sourceMachines) {
        var result = new ArrayList<Component>();
        result.add(Component.translatable(
                "gui.appliedenhancements.duplicate_patterns.group",
                patterns.size()));

        var patternsByMachine = new LinkedHashMap<SourceMachine, Integer>();
        patterns.stream()
                .sorted(Comparator
                        .comparing((SourcePattern pattern) ->
                                pattern.container().getSearchName())
                        .thenComparingLong(pattern -> pattern.slot().containerId())
                        .thenComparingInt(pattern -> pattern.slot().slot()))
                .forEach(pattern -> {
                    SourceMachine machine = sourceMachines.get(pattern.slot().containerId());
                    if (machine != null) {
                        patternsByMachine.merge(machine, 1, Integer::sum);
                    }
                });

        if (!patternsByMachine.isEmpty()) {
            result.add(Component.translatable(
                    "gui.appliedenhancements.duplicate_patterns.sources")
                    .withStyle(ChatFormatting.GRAY));
            int shown = 0;
            for (var entry : patternsByMachine.entrySet()) {
                if (shown == 8) {
                    result.add(Component.translatable(
                            "gui.appliedenhancements.duplicate_patterns.sources.more",
                            patternsByMachine.size() - shown)
                            .withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                result.add(Component.translatable(
                        "gui.appliedenhancements.duplicate_patterns.sources.entry",
                        entry.getKey().displayName(),
                        entry.getValue())
                        .withStyle(ChatFormatting.DARK_GRAY));
                shown++;
            }
        }
        return List.copyOf(result);
    }

    private static List<Component> buildInvalidGroupTooltip(
            List<InvalidSourcePattern> patterns,
            Map<Long, SourceMachine> sourceMachines) {
        var result = new ArrayList<Component>();
        result.add(Component.translatable(
                "gui.appliedenhancements.invalid_patterns.group",
                patterns.size()));

        var patternsByMachine = new LinkedHashMap<SourceMachine, Integer>();
        patterns.stream()
                .sorted(Comparator
                        .comparing((InvalidSourcePattern pattern) ->
                                pattern.container().getSearchName())
                        .thenComparingLong(pattern -> pattern.slot().containerId())
                        .thenComparingInt(pattern -> pattern.slot().slot()))
                .forEach(pattern -> {
                    SourceMachine machine = sourceMachines.get(pattern.slot().containerId());
                    if (machine != null) {
                        patternsByMachine.merge(machine, 1, Integer::sum);
                    }
                });

        if (!patternsByMachine.isEmpty()) {
            result.add(Component.translatable(
                    "gui.appliedenhancements.duplicate_patterns.sources")
                    .withStyle(ChatFormatting.GRAY));
            int shown = 0;
            for (var entry : patternsByMachine.entrySet()) {
                if (shown == 8) {
                    result.add(Component.translatable(
                            "gui.appliedenhancements.duplicate_patterns.sources.more",
                            patternsByMachine.size() - shown)
                            .withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                result.add(Component.translatable(
                        "gui.appliedenhancements.duplicate_patterns.sources.entry",
                        entry.getKey().displayName(),
                        entry.getValue())
                        .withStyle(ChatFormatting.DARK_GRAY));
                shown++;
            }
        }
        return List.copyOf(result);
    }

    public record SourcePattern(
            PatternSlotRef slot, AEKey output, PatternContainerRecord container) {
    }

    public record InvalidSourcePattern(
            PatternSlotRef slot, PatternContainerRecord container) {
    }

    public record SourceMachine(
            PatternContainerGroup group, int ordinal, int groupSize) {
        public Component displayName() {
            var result = Component.empty().append(group.name());
            if (groupSize > 1) {
                result.append(Component.literal(" #" + ordinal));
            }
            return result;
        }
    }

    public record PatternSource(SourceMachine machine, int machineSlot) {
        public List<Component> tooltip() {
            return List.of(
                    Component.translatable(
                            "gui.appliedenhancements.duplicate_patterns.source",
                            machine.displayName())
                            .withStyle(ChatFormatting.GRAY),
                    Component.translatable(
                            "gui.appliedenhancements.duplicate_patterns.source_slot",
                            machineSlot + 1)
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public record BuildResult(
            List<Object> rows,
            Map<PatternSlotRef, PatternSlotRef> displayToSource,
            Map<PatternSlotRef, PatternSource> displaySources) {
    }
}
