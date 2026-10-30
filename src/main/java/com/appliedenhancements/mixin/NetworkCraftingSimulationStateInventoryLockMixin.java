package com.appliedenhancements.mixin;

import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import com.appliedenhancements.runtime.ManualCraftingInventoryLock;
import java.util.ArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes new calculations treat ingredients reserved by confirmation menus as unavailable. */
@Mixin(value = NetworkCraftingSimulationState.class, remap = false)
public abstract class NetworkCraftingSimulationStateInventoryLockMixin {
    @Shadow
    @Final
    private KeyCounter list;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void appliedenhancements$subtractReservedInventory(
            IStorageService storage,
            IActionSource source,
            CallbackInfo callback) {
        var infinite = (com.appliedenhancements.storage.InfinitePlanningInventory) this;
        var candidateKeys = new ArrayList<AEKey>();
        for (var entry : this.list) {
            // Storage caches may report capped amounts even for explicitly infinite cells.
            if (entry.getLongValue() > 0) {
                candidateKeys.add(entry.getKey());
            }
        }
        infinite.appliedenhancements$infiniteKeys().addAll(
                com.appliedenhancements.storage.InfiniteStorageSupport.snapshot(
                        storage.getInventory(), source, candidateKeys));
        com.appliedenhancements.storage.InfiniteStorageSupport.reconcilePlanningSnapshot(
                this.list, storage.getInventory(), source, infinite.appliedenhancements$infiniteKeys());
        ManualCraftingInventoryLock.subtractReservations(
                storage.getInventory(), this.list);
    }
}
