package com.appliedenhancements.mixin;

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
        int hintedSlot = appliedenhancements$getSlotHints().find(handler, itemKey);
        if (hintedSlot < 0 || hintedSlot >= slotCount) {
            if (hintedSlot >= 0) {
                appliedenhancements$getSlotHints().forget(handler, itemKey);
            }
            hintedSlot = -1;
        }

        ItemStack remaining = input;
        boolean hintAccepted = false;
        if (hintedSlot >= 0) {
            int before = remaining.getCount();
            remaining = handler.insertItem(hintedSlot, remaining, simulate);
            hintAccepted = remaining.getCount() < before;
            if (!hintAccepted) {
                appliedenhancements$getSlotHints().forget(handler, itemKey);
            }
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        for (int slot = 0; slot < slotCount; slot++) {
            if (slot == hintedSlot) {
                continue;
            }

            int before = remaining.getCount();
            remaining = handler.insertItem(slot, remaining, simulate);
            if (!hintAccepted && remaining.getCount() < before) {
                appliedenhancements$getSlotHints().remember(handler, itemKey, slot);
                hintAccepted = true;
            }
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

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
