package com.appliedenhancements.storage;

import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Set;

/** Explicit snapshot metadata, never inferred from a Long.MAX_VALUE quantity. */
public interface InfinitePlanningInventory {
    Set<AEKey> appliedenhancements$infiniteKeys();
    Set<AEKey> appliedenhancements$ignoredInfiniteKeys();
    void appliedenhancements$useInfinite(AEKey key, BigInteger amount);
    java.util.Map<AEKey, BigInteger> appliedenhancements$infiniteUsedAmounts();
}
