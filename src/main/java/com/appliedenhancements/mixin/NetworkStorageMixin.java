package com.appliedenhancements.mixin;

import java.util.IdentityHashMap;
import java.util.Map;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.StorageCell;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.NetworkStorage;
import com.appliedenhancements.runtime.NetworkStorageDetectionCache;
import com.appliedenhancements.storage.InfiniteStorageAmounts;
import com.appliedenhancements.storage.InfiniteStorageDetector;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Saturates network listings for cells that can supply more than they advertise. */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageMixin {
    @Unique
    private final Map<MEStorage, NetworkStorageDetectionCache<AEKey>>
            appliedenhancements$infiniteKeys = new IdentityHashMap<>();

    @WrapOperation(method = "getAvailableStacks", at = @At(value = "INVOKE",
            target = "Lappeng/api/storage/MEStorage;getAvailableStacks(Lappeng/api/stacks/KeyCounter;)V"))
    private void appliedenhancements$collectAvailableStacks(
            MEStorage storage,
            KeyCounter output,
            Operation<Void> original) {
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

        NetworkStorageDetectionCache<AEKey> detectedKeys =
                appliedenhancements$infiniteKeys.computeIfAbsent(
                        storage, ignored -> new NetworkStorageDetectionCache<>());
        detectedKeys.beginRefresh();
        try {
            for (var entry : local) {
                var key = entry.getKey();
                long amount = entry.getLongValue();

                if (amount <= 0) {
                    if (output.get(key) != InfiniteStorageAmounts.DISPLAY_AMOUNT) {
                        output.add(key, amount);
                    }
                    continue;
                }

                boolean infinite = amount == InfiniteStorageAmounts.DISPLAY_AMOUNT;
                if (!infinite) {
                    infinite = detectedKeys.resolve(
                            key,
                            amount,
                            () -> InfiniteStorageDetector.probeUnbounded(
                                    storageCell, key, amount));
                }
                output.set(key, InfiniteStorageAmounts.mergeAvailable(
                        output.get(key), amount, infinite));
            }
        } finally {
            detectedKeys.endRefresh();
        }
    }

    @Inject(method = "unmount", at = @At("HEAD"))
    private void appliedenhancements$forgetUnmountedStorage(MEStorage storage, CallbackInfo callback) {
        appliedenhancements$infiniteKeys.remove(storage);
    }
}
