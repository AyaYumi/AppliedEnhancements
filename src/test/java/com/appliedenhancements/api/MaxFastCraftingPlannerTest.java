package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaxFastCraftingPlannerTest {
    @Test
    void explicitFactoryAcceptsNoOpCallbacks() {
        assertNotNull(MaxFastCraftingPlanner.create(1_000, 100, null, null));
    }

    @Test
    void explicitFactoryRejectsInvalidBudgets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MaxFastCraftingPlanner.create(0, 100, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> MaxFastCraftingPlanner.create(1_000, 0, null, null));
    }

    @Test
    void resultDistinguishesAppliedAndFallbackOutcomes() {
        var applied = result(true, null);
        var fallback = result(false, "unsupported_graph");

        assertFalse(applied.shouldFallback());
        assertTrue(fallback.shouldFallback());
    }

    private static MaxFastCraftingPlanner.Result result(
            boolean applied, String fallbackReason) {
        return new MaxFastCraftingPlanner.Result(
                applied, fallbackReason, 0, 0, 0,
                0, 0, 0, false, null, null);
    }
}
