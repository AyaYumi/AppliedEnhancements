package com.github.appliedenhancements.crafting.aelis;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Terminal result for an ordered recipe choice whose only remaining execution
 * path would be an unbounded AE2 per-item replay.
 *
 * <p>This exception must cross the AELIS fallback boundary unchanged. The
 * crafting-menu observer recognizes it and reports a localized explanation to
 * the player instead of presenting a fabricated missing-item result.</p>
 */
public final class AelisOrderedChoicePlanningRejectedException
        extends RuntimeException {
    private final long requestedItems;
    private final long maxLinearNativeItems;

    public AelisOrderedChoicePlanningRejectedException(
            Object requestedKey, long nodeAmount, long requestMultipliers,
            long maxLinearNativeItems) {
        this(String.valueOf(requestedKey),
                saturatedMultiplyPositive(nodeAmount, requestMultipliers),
                maxLinearNativeItems);
    }

    private AelisOrderedChoicePlanningRejectedException(
            String requestedKey, long requestedItems,
            long maxLinearNativeItems) {
        super("Rejected ordered-choice planning for " + requestedKey
                + ": native per-item work " + requestedItems
                + " exceeds limit " + maxLinearNativeItems);
        this.requestedItems = requestedItems;
        this.maxLinearNativeItems = maxLinearNativeItems;
    }

    public long requestedItems() {
        return requestedItems;
    }

    public long maxLinearNativeItems() {
        return maxLinearNativeItems;
    }

    public static AelisOrderedChoicePlanningRejectedException find(
            Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        for (Throwable current = failure;
                current != null && visited.add(current);
                current = current.getCause()) {
            if (current instanceof AelisOrderedChoicePlanningRejectedException rejection) {
                return rejection;
            }
        }
        return null;
    }

    private static long saturatedMultiplyPositive(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        return left > Long.MAX_VALUE / right
                ? Long.MAX_VALUE
                : left * right;
    }
}
