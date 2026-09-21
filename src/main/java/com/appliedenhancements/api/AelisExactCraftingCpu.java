package com.appliedenhancements.api;

import java.math.BigInteger;

/** Optional live CPU status contract. Not a whitelist or an admission gate. */
public interface AelisExactCraftingCpu {
    default java.util.Map<appeng.api.stacks.AEKey, AelisCraftingBatch> aelis$getLastBatches() {
        return java.util.Map.of();
    }
    /** Exact remaining final output, or null when no exact job is active. */
    BigInteger aelis$getRemainingOutput();

    /** Not-yet-dispatched output totals, aggregated over all patterns. Not active/in-flight output. */
    default java.util.Map<appeng.api.stacks.AEKey, BigInteger> aelis$getPendingOutputs() {
        return java.util.Map.of();
    }

    /** Dispatched outputs still awaiting return, including unexposed long windows. */
    default java.util.Map<appeng.api.stacks.AEKey, BigInteger> aelis$getActiveOutputs() {
        return java.util.Map.of();
    }
    /** Actual owned CPU stock, including physical inventory beyond a long window. */
    default java.util.Map<appeng.api.stacks.AEKey, BigInteger> aelis$getStoredOutputs() {
        return java.util.Map.of();
    }
    /** Cumulative produced outputs received by this job, independent of current stock. */
    default java.util.Map<appeng.api.stacks.AEKey, BigInteger> aelis$getCompletedOutputs() {
        return java.util.Map.of();
    }
}
