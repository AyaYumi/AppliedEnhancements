package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AelisCraftingPlannerTest {
    @Test
    void preferredFactoryCreatesAnAelisPlanner() {
        assertNotNull(AelisCraftingPlanner.create(1_000, 100, null, null));
    }

    @Test
    void preferredFactoryNormalizesBudgetsAndAllowsAnAbsentOptionalIndex() {
        assertNotNull(assertDoesNotThrow(() -> AelisCraftingPlanner.create(0, 100, null, null)));
        assertNotNull(assertDoesNotThrow(() -> AelisCraftingPlanner.create(1_000, 0, null, null)));
        assertNotNull(assertDoesNotThrow(() -> AelisCraftingPlanner.create(-1, -1, null, null, null)));
    }

    @Test
    void missingPlanningContextRequestsFallbackInsteadOfThrowing() throws InterruptedException {
        var result = AelisCraftingPlanner.create(1_000, 100, null, null)
                .tryExecute(null, null, 1, false, null);
        assertTrue(result.shouldFallback());
        assertFalse(result.applied());
    }

    @Test
    void resultKeepsAppliedAndFallbackSemantics() {
        var applied = result(true, null);
        var fallback = result(false, "unsupported_graph");

        assertFalse(applied.shouldFallback());
        assertTrue(fallback.shouldFallback());
    }

    private static AelisCraftingPlanner.Result result(
            boolean applied, String fallbackReason) {
        return new AelisCraftingPlanner.Result(
                applied, fallbackReason, 0, 0, 0,
                0, 0, 0, false, null, null);
    }
}
