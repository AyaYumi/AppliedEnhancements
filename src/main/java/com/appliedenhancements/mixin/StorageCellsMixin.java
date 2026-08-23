package com.appliedenhancements.mixin;

import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.appliedenhancements.storage.InfiniteStorageCellRegistry;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Associates item-tag markers with the runtime storage instances AE2 mounts. */
@Mixin(value = StorageCells.class, remap = false)
public abstract class StorageCellsMixin {
    @Inject(method = "getCellInventory", at = @At("RETURN"))
    private static void appliedenhancements$rememberInfiniteItemMarker(
            ItemStack stack,
            ISaveProvider host,
            CallbackInfoReturnable<StorageCell> callback) {
        InfiniteStorageCellRegistry.rememberCellItem(stack, callback.getReturnValue());
    }
}
