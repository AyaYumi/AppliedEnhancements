package com.appliedenhancements.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.StorageCell;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.NetworkStorage;
import com.appliedenhancements.storage.InfiniteStorageAmounts;
import com.appliedenhancements.storage.InfiniteStorageCellRegistry;
import com.appliedenhancements.runtime.ManualCraftingInventoryLock;
import com.appliedenhancements.Config;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Saturates network listings only for explicitly marked infinite cells. */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageMixin {
    @ModifyVariable(method = "extract", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long appliedenhancements$protectReservedCraftingInventory(
            long amount,
            AEKey what,
            long originalAmount,
            Actionable mode,
            IActionSource source) {
        return ManualCraftingInventoryLock.limitExtraction(
                (MEStorage) (Object) this, what, amount, mode, source);
    }

    @WrapOperation(method = "getAvailableStacks", at = @At(value = "INVOKE",
            target = "Lappeng/api/storage/MEStorage;getAvailableStacks(Lappeng/api/stacks/KeyCounter;)V"))
    private void appliedenhancements$collectAvailableStacks(
            MEStorage storage,
            KeyCounter output,
            Operation<Void> original) {
        if (!Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get()) {
            original.call(storage, output);
            return;
        }

        StorageCell storageCell;
        if (storage instanceof StorageCell directCell) {
            storageCell = directCell;
        } else if (storage instanceof DriveWatcher driveWatcher) {
            storageCell = driveWatcher.getCell();
        } else {
            // External storage buses can be expensive to probe and are not cells.
            original.call(storage, output);
            return;
        }

        var local = new KeyCounter();
        original.call(storage, local);
        boolean infiniteStorage = InfiniteStorageCellRegistry.isInfinite(storageCell);

        for (var entry : local) {
            var key = entry.getKey();
            long amount = entry.getLongValue();

            if (amount <= 0) {
                if (output.get(key) != InfiniteStorageAmounts.DISPLAY_AMOUNT) {
                    output.add(key, amount);
                }
                continue;
            }

            output.set(key, InfiniteStorageAmounts.mergeAvailable(
                    output.get(key), amount, infiniteStorage));
        }
    }
}
