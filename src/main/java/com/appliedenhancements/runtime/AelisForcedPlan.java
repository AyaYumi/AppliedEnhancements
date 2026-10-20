package com.appliedenhancements.runtime;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Missing inputs become AE2's external-input requests, never synthetic inventory. */
public final class AelisForcedPlan {
    private AelisForcedPlan() {}

    public static ICraftingPlan prepare(ICraftingPlan source) {
        if (!source.simulation()) return source;
        var missing = new LinkedHashMap<AEKey, BigInteger>();
        source.missingItems().forEach(entry ->
                missing.put(entry.getKey(), BigInteger.valueOf(entry.getLongValue())));
        if (source instanceof AelisBigIntegerCraftAmountsCarrier exact) {
            missing.putAll(exact.appliedenhancements$getBigIntegerMissingAmounts());
        }
        var emitted = new KeyCounter();
        emitted.addAll(source.emittedItems());
        missing.forEach((key, amount) -> {
            if (amount.signum() > 0) {
                emitted.set(key, Math.addExact(emitted.get(key), amount.longValueExact()));
            }
        });
        var nativePlan = new CraftingPlan(source.finalOutput(), source.bytes(), false, source.multiplePaths(),
                source.usedItems(), emitted, new KeyCounter(), source.patternTimes());
        var forced = AelisCycleExecutionApi.copyMetadata(source, nativePlan);
        ((AelisBigIntegerCraftAmountsCarrier) forced).appliedenhancements$setBigIntegerMissingAmounts(Map.of());
        return forced;
    }
}
