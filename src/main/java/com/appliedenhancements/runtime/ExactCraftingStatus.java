package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.appliedenhancements.ae2.ExactCraftingStatusEntry;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** Shared aggregation and entry-copy semantics for third-party CPU status integration. */
public final class ExactCraftingStatus {
    private ExactCraftingStatus() {}
    public static Map<AEKey, BigInteger> pending(Iterable<IPatternDetails> patterns,
            Function<IPatternDetails, BigInteger> remaining) {
        var result = new LinkedHashMap<AEKey, BigInteger>();
        for (var pattern : patterns) {
            var count = remaining.apply(pattern);
            if (count == null || count.signum() <= 0) continue;
            for (var output : pattern.getOutputs()) {
                if (output.amount() > 0) result.merge(output.what(), count.multiply(BigInteger.valueOf(output.amount())), BigInteger::add);
            }
        }
        return Map.copyOf(result);
    }
    public static BigInteger pending(CraftingStatusEntry entry) {
        var exact = ((ExactCraftingStatusEntry) entry).appliedenhancements$getPending();
        return exact == null ? BigInteger.valueOf(entry.getPendingAmount()) : exact;
    }
    public static CraftingStatusEntry copy(CraftingStatusEntry source, CraftingStatusEntry target) {
        ((ExactCraftingStatusEntry) target).appliedenhancements$setLastBatch(
                ((ExactCraftingStatusEntry) source).appliedenhancements$getLastBatch());
        ((ExactCraftingStatusEntry) target).appliedenhancements$setCompleted(
                ((ExactCraftingStatusEntry) source).appliedenhancements$getCompleted());
        ((ExactCraftingStatusEntry) target).appliedenhancements$setStored(
                ((ExactCraftingStatusEntry) source).appliedenhancements$getStored());
        ((ExactCraftingStatusEntry) target).appliedenhancements$setActive(
                ((ExactCraftingStatusEntry) source).appliedenhancements$getActive());
        ((ExactCraftingStatusEntry) target).appliedenhancements$setPending(
                ((ExactCraftingStatusEntry) source).appliedenhancements$getPending());
        return target;
    }
    public static BigInteger active(CraftingStatusEntry entry) {
        var exact = ((ExactCraftingStatusEntry) entry).appliedenhancements$getActive();
        return exact == null ? BigInteger.valueOf(entry.getActiveAmount()) : exact;
    }
    public static BigInteger stored(CraftingStatusEntry entry) {
        var exact = ((ExactCraftingStatusEntry) entry).appliedenhancements$getStored();
        return exact == null ? BigInteger.valueOf(entry.getStoredAmount()) : exact;
    }
}
