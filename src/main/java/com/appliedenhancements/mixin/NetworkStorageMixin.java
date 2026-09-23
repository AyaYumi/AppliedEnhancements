package com.appliedenhancements.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import com.appliedenhancements.storage.InfiniteStorageAmounts;
import com.appliedenhancements.runtime.ManualCraftingInventoryLock;
import com.appliedenhancements.Config;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Infinite semantics apply only to explicitly marked, mounted cells. */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageMixin {
    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lappeng/api/storage/MEStorage;extract(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J"))
    private long appliedenhancements$extractInfiniteCell(MEStorage storage, AEKey key, long amount,
            Actionable mode, IActionSource source, Operation<Long> original) {
        if (amount > 0 && Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get()
                && com.appliedenhancements.storage.InfiniteStorageSupport.isInfinite(storage)) {
            // A successful probe proves the configured key is accessible through this wrapper.
            // Do not modulate the backing cell: an explicit infinite source cannot be depleted.
            return original.call(storage, key, 1L, Actionable.SIMULATE, source) > 0 ? amount : 0;
        }
        return original.call(storage, key, amount, mode, source);
    }
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

        var local = new KeyCounter();
        original.call(storage, local);
        com.appliedenhancements.storage.InfiniteStorageSupport.observe(storage, local);
        boolean infiniteStorage = com.appliedenhancements.storage.InfiniteStorageSupport.isInfinite(storage);

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
