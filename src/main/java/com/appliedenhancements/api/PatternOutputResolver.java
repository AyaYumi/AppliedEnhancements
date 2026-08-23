package com.appliedenhancements.api;

import appeng.api.stacks.AEKey;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Resolves the ordered outputs of an encoded pattern understood by another mod. */
@FunctionalInterface
public interface PatternOutputResolver {
    /**
     * Returns the pattern's outputs in display order, or an empty list when
     * this resolver does not recognize the stack. The first key is used as the
     * primary output for duplicate grouping. Output quantities are deliberately
     * not part of the result.
     */
    List<AEKey> resolveOutputs(ItemStack patternStack, Level level);
}
