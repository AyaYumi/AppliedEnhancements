package com.appliedenhancements.mixin;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.appliedenhancements.Config;
import com.appliedenhancements.storage.WeakSlotHintCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Reuses accepting item-handler slots between export-bus simulation and commit. */
@Mixin(targets = "appeng.parts.automation.HandlerStrategy$1", remap = false)
public abstract class ItemHandlerStrategyMixin {
    @Unique
    private WeakSlotHintCache<IItemHandler, AEItemKey> appliedenhancements$slotHints;

    @WrapOperation(method = "insert(Lnet/neoforged/neoforge/items/IItemHandler;Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/items/ItemHandlerHelper;insertItem(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack appliedenhancements$insertUsingSlotHint(
            IItemHandler handler,
            ItemStack input,
            boolean simulate,
            Operation<ItemStack> original) {
        if (!Config.ENABLE_IO_BUS_OPTIMIZATION.get() || handler == null || input.isEmpty()) {
            return original.call(handler, input, simulate);
        }

        var itemKey = AEItemKey.of(input);
        if (itemKey == null) {
            return original.call(handler, input, simulate);
        }

        int slotCount = handler.getSlots();
        var cache = appliedenhancements$getSlotHints();
        List<Integer> hints = cache.findAll(handler, itemKey);
        var visited = new BitSet(slotCount);
        var acceptingSlots = new ArrayList<Integer>(hints.size());
        ItemStack remaining = input;
        for (int hintedSlot : hints) {
            if (hintedSlot < 0 || hintedSlot >= slotCount || visited.get(hintedSlot)) {
                continue;
            }
            visited.set(hintedSlot);
            int before = remaining.getCount();
            remaining = handler.insertItem(hintedSlot, remaining, simulate);
            if (remaining.getCount() < before) {
                acceptingSlots.add(hintedSlot);
            }
            if (remaining.isEmpty()) {
                cache.rememberAll(handler, itemKey, acceptingSlots);
                return ItemStack.EMPTY;
            }
        }

        for (int slot = 0; slot < slotCount; slot++) {
            if (visited.get(slot)) {
                continue;
            }
            visited.set(slot);

            int before = remaining.getCount();
            remaining = handler.insertItem(slot, remaining, simulate);
            if (remaining.getCount() < before) {
                acceptingSlots.add(slot);
            }
            if (remaining.isEmpty()) {
                cache.rememberAll(handler, itemKey, acceptingSlots);
                return ItemStack.EMPTY;
            }
        }

        cache.rememberAll(handler, itemKey, acceptingSlots);
        return remaining;
    }

    @Unique
    private WeakSlotHintCache<IItemHandler, AEItemKey> appliedenhancements$getSlotHints() {
        if (appliedenhancements$slotHints == null) {
            appliedenhancements$slotHints = new WeakSlotHintCache<>(128);
        }
        return appliedenhancements$slotHints;
    }
}
