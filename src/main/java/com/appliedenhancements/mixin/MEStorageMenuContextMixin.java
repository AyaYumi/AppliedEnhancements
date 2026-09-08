package com.appliedenhancements.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.menu.me.common.MEStorageMenu;
import com.appliedenhancements.integration.ae2.NetworkItemExtractionMenuBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Implements exact, capacity-bounded extraction for the context menu. */
@Mixin(value = MEStorageMenu.class, remap = false)
public abstract class MEStorageMenuContextMixin
        implements NetworkItemExtractionMenuBridge {
    @Shadow
    @Final
    protected MEStorage storage;

    @Shadow
    @Final
    protected IEnergySource powerSource;

    @Shadow
    @Nullable
    protected abstract AEKey getStackBySerial(long serial);

    @Override
    public long appliedenhancements$extractNetworkItem(
            long serial, long requestedAmount) {
        var menu = (MEStorageMenu) (Object) this;
        if (requestedAmount <= 0
                || storage == null || powerSource == null || !menu.isPowered()
                || !(menu.getPlayer() instanceof ServerPlayer player)
                || !(getStackBySerial(serial) instanceof AEItemKey key)) {
            return 0;
        }

        int capacity = appliedenhancements$getInventoryCapacity(player, key);
        if (capacity <= 0) {
            return 0;
        }
        long wanted = Math.min(requestedAmount, capacity);
        long available = storage.extract(
                key, wanted, Actionable.SIMULATE, menu.getActionSource());
        if (available <= 0) {
            return 0;
        }
        long extracted = StorageHelper.poweredExtraction(
                powerSource,
                storage,
                key,
                Math.min(wanted, available),
                menu.getActionSource());
        if (extracted <= 0) {
            return 0;
        }

        int remainder = appliedenhancements$placeInPlayerInventory(
                player, key, (int) extracted);
        if (remainder != 0) {
            // The server thread cannot normally change the inventory between
            // capacity calculation and placement. Reinsert defensively if a
            // third-party hook did so anyway.
            long returned = StorageHelper.poweredInsert(
                    powerSource,
                    storage,
                    key,
                    remainder,
                    menu.getActionSource());
            extracted -= returned;
            remainder -= (int) returned;
            if (remainder > 0) {
                appliedenhancements$dropRemainder(player, key, remainder);
            }
        }

        player.getInventory().setChanged();
        menu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        return extracted;
    }

    private static int appliedenhancements$getInventoryCapacity(
            ServerPlayer player, AEItemKey key) {
        long capacity = 0;
        int maxStackSize = key.getMaxStackSize();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                capacity += maxStackSize;
            } else if (key.matches(stack)) {
                capacity += Math.max(0, maxStackSize - stack.getCount());
            }
            if (capacity >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) capacity;
    }

    private static int appliedenhancements$placeInPlayerInventory(
            ServerPlayer player, AEItemKey key, int amount) {
        int remaining = amount;
        int maxStackSize = key.getMaxStackSize();
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) {
                return 0;
            }
            if (!stack.isEmpty() && key.matches(stack)
                    && stack.getCount() < maxStackSize) {
                int moved = Math.min(remaining, maxStackSize - stack.getCount());
                stack.grow(moved);
                remaining -= moved;
            }
        }
        for (int slot = 0;
                slot < player.getInventory().items.size() && remaining > 0;
                slot++) {
            if (player.getInventory().items.get(slot).isEmpty()) {
                int moved = Math.min(remaining, maxStackSize);
                player.getInventory().items.set(slot, key.toStack(moved));
                remaining -= moved;
            }
        }
        return remaining;
    }

    private static void appliedenhancements$dropRemainder(
            ServerPlayer player, AEItemKey key, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int dropped = Math.min(remaining, key.getMaxStackSize());
            player.drop(key.toStack(dropped), false);
            remaining -= dropped;
        }
    }
}
