package com.github.appliedenhancements.crafting.aelis;

import java.util.Collections;
import java.util.IdentityHashMap;

/** A terminal result: a rejected batch must not become an unbounded native replay. */
public final class AelisPlanningLimitException extends RuntimeException {
    public AelisPlanningLimitException(String reason) {
        super("AELIS cannot safely delegate this planning request: " + reason);
    }

    public static boolean rejectsNativeFallback(String reason, long requestedAmount) {
        return requestedAmount > 1_000_000L || reason != null
                && (reason.contains("overflow") || reason.contains("native_boundary_work_limit"));
    }

    public static boolean causedBy(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof AelisPlanningLimitException) return true;
        }
        return false;
    }
}
