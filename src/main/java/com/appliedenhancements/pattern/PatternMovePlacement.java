package com.appliedenhancements.pattern;

import appeng.api.inventories.InternalInventory;
import com.appliedenhancements.api.PatternSlotRef;
import net.minecraft.world.item.ItemStack;

public record PatternMovePlacement(
        PatternSlotRef source,
        long targetId,
        InternalInventory targetInventory,
        int targetSlot,
        ItemStack stack) {
}
