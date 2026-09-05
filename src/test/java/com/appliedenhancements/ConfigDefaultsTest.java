package com.appliedenhancements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appliedenhancements.api.AelisCycleSeedPolicy;
import org.junit.jupiter.api.Test;

class ConfigDefaultsTest {
    @Test
    void craftingOrderDefaultMatchesNativeIntegerLimit() {
        assertEquals((long) Integer.MAX_VALUE,
                Config.MAX_CRAFTING_ORDER_AMOUNT.getDefault());
    }

    @Test
    void userSelectedPresentationAndInfiniteListingDefaultsAreDisabled() {
        assertFalse(Config.ENABLE_ENHANCED_MATERIAL_CALCULATION.getDefault());
        assertFalse(Config.ENABLE_PROGRESS_DISPLAY.getDefault());
        assertFalse(Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.getDefault());
    }

    @Test
    void aelisDefaultsRemainConservative() {
        assertFalse(Config.ENABLE_AUTOMATIC_AELIS_PLANNER.getDefault());
        assertFalse(Config.AELIS_DIAGNOSTICS.getDefault());
        assertEquals(100000, Config.AELIS_MAX_NODES.getDefault());
        assertEquals(2000, Config.AELIS_COMPILE_BUDGET_MS.getDefault());
        assertEquals(256, Config.CYCLE_SOLVER_MAX_SCC_NODES.getDefault());
        assertEquals(1_000_000, Config.CYCLE_SOLVER_MAX_SEARCH_STATES.getDefault());
        assertEquals(1000, Config.CYCLE_SOLVER_BUDGET_MS.getDefault());
        assertEquals(
                AelisCycleSeedPolicy.PRESERVE_MINIMUM,
                Config.CYCLE_SEED_POLICY.getDefault());
    }

    @Test
    void validatedModpackPerformanceProfileIsTheDefault() {
        assertTrue(Config.ENABLE_LONG_RANGE_CRAFTING.getDefault());
        assertTrue(Config.ENABLE_PATTERN_CACHING.getDefault());
        assertEquals(32, Config.PATTERN_CACHE_SIZE.getDefault());
        assertTrue(Config.ENABLE_STORAGE_BUS_SLOT_INDEX.getDefault());
        assertTrue(Config.ENABLE_IO_BUS_OPTIMIZATION.getDefault());
    }

    @Test
    void unifiedSpecUsesFunctionOrientedPathsOnly() {
        var values = Config.SPEC.getValues();
        assertTrue(values.contains("crafting.enable_long_range_crafting"));
        assertTrue(values.contains("crafting.max_crafting_order_amount"));
        assertTrue(values.contains("crafting.enable_progress_display"));
        assertTrue(values.contains("crafting.enable_enhanced_material_calculation"));
        assertTrue(values.contains("crafting.aelis.enable_automatic_planner"));
        assertTrue(values.contains("crafting.aelis.max_nodes"));
        assertTrue(values.contains("crafting.aelis.compile_budget_ms"));
        assertTrue(values.contains("crafting.aelis.enable_diagnostics"));
        assertTrue(values.contains("crafting.aelis.cycle_solver.max_scc_nodes"));
        assertTrue(values.contains("crafting.aelis.cycle_solver.max_search_states"));
        assertTrue(values.contains("crafting.aelis.cycle_solver.budget_ms"));
        assertTrue(values.contains("crafting.aelis.cycle_solver.seed_policy"));
        assertTrue(values.contains("performance.pattern_cache.enabled"));
        assertTrue(values.contains("performance.pattern_cache.max_entries_per_pattern"));
        assertTrue(values.contains("performance.storage_bus.enable_slot_index"));
        assertTrue(values.contains("performance.io_bus.enable_slot_routing"));
        assertTrue(values.contains("storage.infinite.enable_listing_limit_bypass"));

        assertFalse(values.contains("caching.enable_pattern_caching"));
        assertFalse(values.contains("crafting_plan.enable_enhanced_material_calculation"));
        assertFalse(values.contains("storage_bus.enable_infinite_storage_limit_bypass"));
    }
}
