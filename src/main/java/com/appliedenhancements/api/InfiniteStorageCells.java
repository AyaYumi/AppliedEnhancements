package com.appliedenhancements.api;

import com.appliedenhancements.AppliedEnhancements;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Public identifiers used to opt storage-cell items into infinite display. */
public final class InfiniteStorageCells {
    /**
     * Item tag recognized by inventory mounting and client tooltips.
     *
     * <p>Data packs and KubeJS may add storage-cell item IDs to
     * {@code #appliedenhancements:infinite_storage_cells}.</p>
     */
    public static final TagKey<Item> ITEM_TAG = TagKey.create(
            Registries.ITEM,
            AppliedEnhancements.id("infinite_storage_cells"));

    private InfiniteStorageCells() {
    }

    public static boolean isMarked(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(ITEM_TAG);
    }
}
