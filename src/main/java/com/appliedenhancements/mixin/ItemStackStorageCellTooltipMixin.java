package com.appliedenhancements.mixin;

import java.util.ArrayList;
import java.util.Optional;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.items.storage.StorageCellTooltipComponent;
import com.appliedenhancements.api.InfiniteStorageCells;
import com.appliedenhancements.storage.InfiniteStorageAmounts;
import com.appliedenhancements.storage.InfiniteStorageCellRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rewrites infinite storage-cell content previews to use the shared sentinel. */
@Mixin(ItemStack.class)
public abstract class ItemStackStorageCellTooltipMixin {
    @Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
    private void appliedenhancements$markInfiniteCellContents(
            CallbackInfoReturnable<Optional<TooltipComponent>> callback) {
        var original = callback.getReturnValue();
        if (original.isEmpty() || !(original.get() instanceof StorageCellTooltipComponent component)) {
            return;
        }

        var stack = (ItemStack) (Object) this;
        try {
            var storage = StorageCells.getCellInventory(stack, null);
            if (storage == null) {
                return;
            }

            if (!InfiniteStorageCells.isMarked(stack)
                    && !InfiniteStorageCellRegistry.isInfinite(storage)) {
                return;
            }

            boolean changed = false;
            var content = new ArrayList<GenericStack>(component.content().size());
            for (var entry : component.content()) {
                if (entry.amount() != InfiniteStorageAmounts.DISPLAY_AMOUNT) {
                    content.add(new GenericStack(entry.what(), InfiniteStorageAmounts.DISPLAY_AMOUNT));
                    changed = true;
                } else {
                    content.add(entry);
                }
            }

            if (changed) {
                callback.setReturnValue(Optional.of(new StorageCellTooltipComponent(
                        component.upgrades(),
                        content,
                        component.hasMoreContent(),
                        true)));
            }
        } catch (RuntimeException ignored) {
            // A third-party cell tooltip should remain usable even if its client inventory is unavailable.
        }
    }
}
