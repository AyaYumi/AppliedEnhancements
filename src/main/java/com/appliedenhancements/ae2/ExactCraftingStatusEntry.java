package com.appliedenhancements.ae2;

import java.math.BigInteger;

/** Optional exact pending count carried with the same incremental status entry as its long projection. */
public interface ExactCraftingStatusEntry {
    com.appliedenhancements.api.AelisCraftingBatch appliedenhancements$getLastBatch();
    void appliedenhancements$setLastBatch(com.appliedenhancements.api.AelisCraftingBatch batch);
    BigInteger appliedenhancements$getPending();
    void appliedenhancements$setPending(BigInteger amount);
    BigInteger appliedenhancements$getActive();
    void appliedenhancements$setActive(BigInteger amount);
    BigInteger appliedenhancements$getStored();
    void appliedenhancements$setStored(BigInteger amount);
    BigInteger appliedenhancements$getCompleted();
    void appliedenhancements$setCompleted(BigInteger amount);
}
