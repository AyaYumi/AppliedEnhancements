package com.appliedenhancements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void maxFastDefaultsRemainConservative() {
        assertFalse(Config.ENABLE_AUTOMATIC_MAX_FAST_PLANNER.getDefault());
        assertFalse(Config.MAX_FAST_DIAGNOSTICS.getDefault());
        assertEquals(100000, Config.MAX_FAST_MAX_NODES.getDefault());
        assertEquals(2000, Config.MAX_FAST_COMPILE_BUDGET_MS.getDefault());
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
        assertTrue(values.contains("crafting.max_fast.enable_automatic_planner"));
        assertTrue(values.contains("crafting.max_fast.max_nodes"));
        assertTrue(values.contains("crafting.max_fast.compile_budget_ms"));
        assertTrue(values.contains("crafting.max_fast.enable_diagnostics"));
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
