package com.appliedenhancements.cache;

import appeng.api.stacks.AEKey;

/**
 * Cache record for AE2 pattern remaining key (container item) lookups.
 * Reduces redundant container item resolution during crafting calculation.
 */
public record RemainingKeyCache(AEKey input, AEKey output) {
}
