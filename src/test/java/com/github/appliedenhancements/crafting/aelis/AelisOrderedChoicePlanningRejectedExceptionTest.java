package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

class AelisOrderedChoicePlanningRejectedExceptionTest {
    @Test
    void exposesBoundedWorkForLocalizedFailureMessage() {
        var rejection = new AelisOrderedChoicePlanningRejectedException(
                "diamond", 1, 10_000_000_000L, 1_000_000L);

        assertEquals(10_000_000_000L, rejection.requestedItems());
        assertEquals(1_000_000L, rejection.maxLinearNativeItems());
    }

    @Test
    void overflowingWorkSaturatesInsteadOfWrapping() {
        var rejection = new AelisOrderedChoicePlanningRejectedException(
                "diamond", Long.MAX_VALUE, 2, 1_000_000L);

        assertEquals(Long.MAX_VALUE, rejection.requestedItems());
    }

    @Test
    void findsRejectionThroughFutureWrappers() {
        var rejection = new AelisOrderedChoicePlanningRejectedException(
                "diamond", 1, 2_000_000, 1_000_000);
        var wrapped = new RuntimeException(new ExecutionException(rejection));

        assertSame(rejection,
                AelisOrderedChoicePlanningRejectedException.find(wrapped));
        assertNull(AelisOrderedChoicePlanningRejectedException.find(
                new IllegalStateException("other")));
    }
}
