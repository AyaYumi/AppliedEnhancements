package com.appliedenhancements.cache;

import appeng.api.stacks.AEKey;
import net.minecraft.world.level.Level;

/**
 * Cache record for AE2 pattern input validity results.
 * Reduces redundant validation calls during crafting calculation.
 */
public record PatternValidityCache(AEKey input, Level level, boolean valid) {
}
