package com.appliedenhancements.mixin;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.menu.implementations.PatternAccessTermMenu;
import com.appliedenhancements.api.PatternBatchMoveApi;
import com.appliedenhancements.api.PatternDuplicateApi;
import com.appliedenhancements.api.PatternSlotRef;
import com.appliedenhancements.integration.ae2.PatternContainerTrackerBridge;
import com.appliedenhancements.pattern.PatternMovePlacement;
import com.appliedenhancements.pattern.PatternMoveSlotIndex;
import com.appliedenhancements.pattern.PatternMoveTargetSlot;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = PatternAccessTermMenu.class, remap = false)
public abstract class PatternAccessTermMenuBatchMoveMixin
        implements PatternBatchMoveApi.MenuExtension {
    @Shadow
    @Final
    private Long2ObjectOpenHashMap<Object> byId;

    @Override
    public PatternBatchMoveApi.Result movePatterns(
            ServerPlayer player, PatternBatchMoveApi.Request request) {
        if (((PatternAccessTermMenu) (Object) this).isClientSide()) {
            return PatternBatchMoveApi.Result.failure(PatternBatchMoveApi.Failure.INVALID_MENU);
        }

        var sources = new LinkedHashSet<>(request.sources());
        var targetIds = new LinkedHashSet<>(request.targetContainerIds());
        int preferredTargetSlot = request.preferredTargetSlot();
        if (sources.isEmpty() || sources.size() > PatternBatchMoveApi.MAX_SOURCES
                || targetIds.isEmpty() || targetIds.size() > PatternBatchMoveApi.MAX_TARGETS) {
            return PatternBatchMoveApi.Result.failure(PatternBatchMoveApi.Failure.INVALID_MENU);
        }

        var targetInventories = new LinkedHashMap<Long, InternalInventory>();
        for (long targetId : targetIds) {
            Object tracker = byId.get(targetId);
            if (!(tracker instanceof PatternContainerTrackerBridge bridge)) {
                return PatternBatchMoveApi.Result.failure(
                        PatternBatchMoveApi.Failure.INVALID_TARGET);
            }
            targetInventories.put(targetId, bridge.appliedenhancements$getServerInventory());
        }

        var sourceStacks = new LinkedHashMap<PatternSlotRef, ItemStack>();
        for (PatternSlotRef source : sources) {
            Object tracker = byId.get(source.containerId());
            if (!(tracker instanceof PatternContainerTrackerBridge bridge)) {
                return PatternBatchMoveApi.Result.failure(
                        PatternBatchMoveApi.Failure.INVALID_SOURCE);
            }
            InternalInventory inventory = bridge.appliedenhancements$getServerInventory();
            if (source.slot() >= inventory.size()) {
                return PatternBatchMoveApi.Result.failure(
                        PatternBatchMoveApi.Failure.INVALID_SOURCE);
            }
            ItemStack stack = inventory.getStackInSlot(source.slot());
            if (stack.getCount() != 1 || !PatternDetailsHelper.isEncodedPattern(stack)) {
                return PatternBatchMoveApi.Result.failure(
                        PatternBatchMoveApi.Failure.INVALID_SOURCE);
            }
            if (PatternDuplicateApi.isInvalidPattern(stack, player.level())) {
                continue;
            }
            if (targetIds.contains(source.containerId())) {
                return PatternBatchMoveApi.Result.failure(PatternBatchMoveApi.Failure.SAME_TARGET);
            }
            sourceStacks.put(source, stack.copy());
        }

        var emptySlots = new PatternMoveSlotIndex<>(
                targetInventories,
                InternalInventory::size,
                (inventory, slot) -> inventory.getStackInSlot(slot).isEmpty());
        var placements = new ArrayList<PatternMovePlacement>(sourceStacks.size());
        for (var sourceEntry : sourceStacks.entrySet()) {
            PatternMovePlacement placement = findPlacement(
                    sourceEntry.getKey(),
                    sourceEntry.getValue(),
                    emptySlots,
                    preferredTargetSlot);
            if (placement == null) {
                return PatternBatchMoveApi.Result.failure(
                        PatternBatchMoveApi.Failure.NOT_ENOUGH_SPACE);
            }
            placements.add(placement);
            preferredTargetSlot = -1;
        }

        var targetSnapshots = new LinkedHashMap<PatternMoveTargetSlot, ItemStack>();
        for (PatternMovePlacement placement : placements) {
            targetSnapshots.put(
                    new PatternMoveTargetSlot(placement.targetId(), placement.targetSlot()),
                    placement.targetInventory().getStackInSlot(placement.targetSlot()).copy());
        }

        try {
            for (PatternMovePlacement placement : placements) {
                ItemStack remainder = placement.targetInventory().insertItem(
                        placement.targetSlot(), placement.stack(), false);
                if (!remainder.isEmpty()) {
                    throw new IllegalStateException("target rejected a preflighted pattern");
                }
            }
            for (var sourceEntry : sourceStacks.entrySet()) {
                PatternSlotRef source = sourceEntry.getKey();
                var tracker = (PatternContainerTrackerBridge) byId.get(source.containerId());
                tracker.appliedenhancements$getServerInventory()
                        .setItemDirect(source.slot(), ItemStack.EMPTY);
            }
            return PatternBatchMoveApi.Result.success(placements.size());
        } catch (RuntimeException failure) {
            for (var snapshot : targetSnapshots.entrySet()) {
                InternalInventory inventory = targetInventories.get(
                        snapshot.getKey().containerId());
                if (inventory != null && snapshot.getKey().slot() < inventory.size()) {
                    inventory.setItemDirect(snapshot.getKey().slot(), snapshot.getValue());
                }
            }
            for (var sourceEntry : sourceStacks.entrySet()) {
                PatternSlotRef source = sourceEntry.getKey();
                Object tracker = byId.get(source.containerId());
                if (tracker instanceof PatternContainerTrackerBridge bridge
                        && source.slot() < bridge.appliedenhancements$getServerInventory().size()) {
                    bridge.appliedenhancements$getServerInventory()
                            .setItemDirect(source.slot(), sourceEntry.getValue());
                }
            }
            return PatternBatchMoveApi.Result.failure(PatternBatchMoveApi.Failure.APPLY_FAILED);
        }
    }

    private static PatternMovePlacement findPlacement(
            PatternSlotRef source,
            ItemStack stack,
            PatternMoveSlotIndex<InternalInventory> emptySlots,
            int preferredSlot) {
        var candidate = emptySlots.findAndReserve(
                stack,
                preferredSlot,
                (inventory, slot, candidateStack) ->
                        inventory.getStackInSlot(slot).isEmpty()
                                && inventory.insertItem(slot, candidateStack, true).isEmpty());
        return candidate == null ? null : new PatternMovePlacement(
                source,
                candidate.targetId(),
                candidate.target(),
                candidate.slot(),
                stack);
    }

}
