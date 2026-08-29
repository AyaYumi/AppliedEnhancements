package com.appliedenhancements.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.appliedenhancements.Config;
import com.appliedenhancements.storage.StorageBusSlotIndex;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Reuses AE2's periodic external-inventory listing as a candidate-slot index.
 * Cached slots are hints only: every candidate is revalidated by AE2's own
 * extraction helper, and incomplete lookups fall back to the original scan.
 */
@Mixin(targets = "appeng.me.storage.ExternalStorageFacade$ItemHandlerFacade", remap = false)
public abstract class ExternalStorageFacadeItemHandlerMixin {
    @Shadow
    @Final
    private IItemHandler handler;

    @Shadow
    private static int extractFromHandler(
            IItemHandler handler,
            int slot,
            AEItemKey itemKey,
            int maxExtract,
            Actionable actionable) {
        throw new AssertionError("Mixin shadow was not transformed");
    }

    @Unique
    private StorageBusSlotIndex<AEItemKey> appliedenhancements$slotIndex;

    @Unique
    private int appliedenhancements$lastObservedSlot = -1;

    @Unique
    private AEItemKey appliedenhancements$lastObservedKey;

    @Inject(method = "getStackInSlot", at = @At("RETURN"))
    private void appliedenhancements$rememberObservedSlot(
            int slot,
            CallbackInfoReturnable<GenericStack> cir) {
        var stack = cir.getReturnValue();
        if (Config.ENABLE_IO_BUS_OPTIMIZATION.get()
                && stack != null
                && stack.what() instanceof AEItemKey itemKey) {
            appliedenhancements$lastObservedSlot = slot;
            appliedenhancements$lastObservedKey = itemKey;
        } else {
            appliedenhancements$clearObservedSlot();
        }
    }

    @Inject(method = "getAvailableStacks", at = @At("HEAD"))
    private void appliedenhancements$beginSlotIndexRebuild(KeyCounter output, CallbackInfo ci) {
        if (Config.ENABLE_STORAGE_BUS_SLOT_INDEX.get()) {
            appliedenhancements$getSlotIndex().beginRebuild();
        } else if (appliedenhancements$slotIndex != null) {
            appliedenhancements$slotIndex.abortRebuild();
        }
    }

    @WrapOperation(method = "getAvailableStacks", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/items/IItemHandler;getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack appliedenhancements$recordListedSlot(
            IItemHandler inventory,
            int slot,
            Operation<ItemStack> original) {
        var stack = original.call(inventory, slot);
        if (appliedenhancements$slotIndex != null && !stack.isEmpty()) {
            appliedenhancements$slotIndex.record(AEItemKey.of(stack), slot);
        }
        return stack;
    }

    @Inject(method = "getAvailableStacks", at = @At("RETURN"))
    private void appliedenhancements$publishSlotIndex(KeyCounter output, CallbackInfo ci) {
        if (appliedenhancements$slotIndex != null) {
            appliedenhancements$slotIndex.commitRebuild();
        }
    }

    @WrapMethod(method = "extractExternal")
    private int appliedenhancements$extractFromIndexedSlots(
            AEKey what,
            int amount,
            Actionable mode,
            Operation<Integer> original) {
        if (amount <= 0
                || !(what instanceof AEItemKey itemKey)
                || !appliedenhancements$hasFastPath(itemKey)) {
            return original.call(what, amount, mode);
        }

        int observedSlot = -1;
        int indexedExtracted = 0;
        if (Config.ENABLE_IO_BUS_OPTIMIZATION.get()
                && itemKey.equals(appliedenhancements$lastObservedKey)) {
            observedSlot = appliedenhancements$lastObservedSlot;
            appliedenhancements$clearObservedSlot();
            if (observedSlot >= 0 && observedSlot < handler.getSlots()) {
                indexedExtracted = extractFromHandler(
                        handler, observedSlot, itemKey, amount, mode);
            }
        }

        if (indexedExtracted < amount
                && Config.ENABLE_STORAGE_BUS_SLOT_INDEX.get()
                && appliedenhancements$slotIndex != null) {
            indexedExtracted += appliedenhancements$extractCandidates(
                    appliedenhancements$slotIndex.candidates(itemKey),
                    itemKey,
                    amount - indexedExtracted,
                    mode,
                    observedSlot);
        }

        if (indexedExtracted >= amount) {
            return amount;
        }

        // Simulations do not modify candidate slots. Combining a partial indexed
        // result with the fallback would count those same slots twice.
        if (mode == Actionable.SIMULATE) {
            return original.call(what, amount, mode);
        }

        return indexedExtracted + original.call(what, amount - indexedExtracted, mode);
    }

    @Unique
    private StorageBusSlotIndex<AEItemKey> appliedenhancements$getSlotIndex() {
        if (appliedenhancements$slotIndex == null) {
            appliedenhancements$slotIndex = new StorageBusSlotIndex<>();
        }
        return appliedenhancements$slotIndex;
    }

    @Unique
    private boolean appliedenhancements$hasFastPath(AEItemKey itemKey) {
        boolean observedSlotAvailable = Config.ENABLE_IO_BUS_OPTIMIZATION.get()
                && appliedenhancements$lastObservedSlot >= 0
                && itemKey.equals(appliedenhancements$lastObservedKey);
        boolean indexedSlotsAvailable = Config.ENABLE_STORAGE_BUS_SLOT_INDEX.get()
                && appliedenhancements$slotIndex != null
                && !appliedenhancements$slotIndex.candidates(itemKey).isEmpty();
        return observedSlotAvailable || indexedSlotsAvailable;
    }

    @Unique
    private void appliedenhancements$clearObservedSlot() {
        appliedenhancements$lastObservedSlot = -1;
        appliedenhancements$lastObservedKey = null;
    }

    @Unique
    private int appliedenhancements$extractCandidates(
            StorageBusSlotIndex.CandidateSlots candidates,
            AEItemKey itemKey,
            int amount,
            Actionable mode,
            int slotToSkip) {
        int extracted = 0;
        int slotCount = handler.getSlots();

        for (int i = 0; i < candidates.size() && extracted < amount; i++) {
            int slot = candidates.get(i);
            if (slot < 0 || slot >= slotCount || slot == slotToSkip) {
                continue;
            }

            extracted += extractFromHandler(
                    handler, slot, itemKey, amount - extracted, mode);
        }

        return extracted;
    }
}
