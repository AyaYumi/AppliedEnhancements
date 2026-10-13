package com.appliedenhancements.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import com.appliedenhancements.storage.InfiniteStorageNetworkAccess;
import com.appliedenhancements.storage.InfiniteStorageAmounts;
import com.appliedenhancements.storage.MountedInfiniteStorage;
import com.appliedenhancements.storage.InfiniteStorageSupport;
import com.appliedenhancements.runtime.ManualCraftingInventoryLock;
import com.appliedenhancements.Config;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Infinite semantics apply only to explicitly marked, mounted cells. */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageMixin implements InfiniteStorageNetworkAccess {
    // Mixin can omit a field initializer when merging constructor bytecode.
    // Resolve the cache on first access instead of depending on constructor injection.
    @Unique private volatile MountedInfiniteStorage appliedenhancements$infiniteStorageState;

    @Unique
    private MountedInfiniteStorage appliedenhancements$infiniteStorageState() {
        var state = appliedenhancements$infiniteStorageState;
        if (state == null) {
            synchronized (this) {
                state = appliedenhancements$infiniteStorageState;
                if (state == null) {
                    state = new MountedInfiniteStorage();
                    appliedenhancements$infiniteStorageState = state;
                }
            }
        }
        return state;
    }

    @Override
    public Set<AEKey> appliedenhancements$snapshotInfiniteKeys(
            IActionSource source, Iterable<AEKey> candidateKeys) {
        var mountedInfinite = appliedenhancements$infiniteStorageState().snapshot();
        return InfiniteStorageSupport.snapshotInfiniteKeys(
                mountedInfinite, ignored -> true, candidateKeys, source);
    }

    @Inject(method = "mount", at = @At("RETURN"))
    private void appliedenhancements$rememberMountedCell(int priority, MEStorage storage,
            CallbackInfo callback) {
        appliedenhancements$refreshInfiniteStorage(storage);
    }

    @Inject(method = "unmount", at = @At("RETURN"))
    private void appliedenhancements$forgetUnmountedCell(MEStorage storage, CallbackInfo callback) {
        appliedenhancements$infiniteStorageState().forget(storage);
    }

    @Unique
    private boolean appliedenhancements$refreshInfiniteStorage(MEStorage storage) {
        return appliedenhancements$infiniteStorageState().refresh(storage);
    }

    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lappeng/api/storage/MEStorage;extract(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J"))
    private long appliedenhancements$extractInfiniteCell(MEStorage storage, AEKey key, long amount,
            Actionable mode, IActionSource source, Operation<Long> original) {
        if (amount > 0 && Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get()
                && !InfiniteStorageSupport.isPhysicalExtract()
                && appliedenhancements$infiniteStorageState().isInfinite(storage)) {
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
        boolean infiniteStorage = appliedenhancements$refreshInfiniteStorage(storage);

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
