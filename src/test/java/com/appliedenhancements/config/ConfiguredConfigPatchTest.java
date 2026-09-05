package com.appliedenhancements.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appliedenhancements.Config;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.concurrent.SynchronizedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfiguredConfigPatchTest {
    private static final String PROGRESS = "crafting.enable_progress_display";
    private static final String MATERIALS = "crafting.enable_enhanced_material_calculation";
    private static final String PLANNER = "crafting.aelis.enable_automatic_planner";
    private static final String DIAGNOSTICS = "crafting.aelis.enable_diagnostics";

    @Test
    void enablingMaterialsAndDiagnosticsPreservesProgressAndPlanner() {
        var current = customizedConfig();
        current.set(PROGRESS, true);
        current.set(PLANNER, true);
        var changes = emptyConfig();
        changes.set(MATERIALS, true);
        changes.set(DIAGNOSTICS, true);

        ConfiguredConfigPatch.apply(current, changes);
        Config.SPEC.correct(current);

        assertAllEnabled(current);
        assertCustomValues(current);
    }

    @Test
    void enablingProgressAndPlannerPreservesMaterialsAndDiagnostics() {
        var current = customizedConfig();
        current.set(MATERIALS, true);
        current.set(DIAGNOSTICS, true);
        var changes = emptyConfig();
        changes.set(PROGRESS, true);
        changes.set(PLANNER, true);

        ConfiguredConfigPatch.apply(current, changes);
        Config.SPEC.correct(current);

        assertAllEnabled(current);
        assertCustomValues(current);
    }

    @Test
    void sequentialSavesCanDisableOneOptionWithoutResettingTheOthers() {
        var current = customizedConfig();
        for (String key : List.of(PROGRESS, MATERIALS, PLANNER, DIAGNOSTICS)) {
            var changes = emptyConfig();
            changes.set(key, true);
            ConfiguredConfigPatch.apply(current, changes);
            Config.SPEC.correct(current);
        }
        assertAllEnabled(current);

        var changes = emptyConfig();
        changes.set(DIAGNOSTICS, false);
        ConfiguredConfigPatch.apply(current, changes);
        Config.SPEC.correct(current);

        assertEquals(true, current.get(PROGRESS));
        assertEquals(true, current.get(MATERIALS));
        assertEquals(true, current.get(PLANNER));
        assertEquals(false, current.get(DIAGNOSTICS));
        assertCustomValues(current);
    }

    @Test
    void emptyUpdatePreservesCustomizedValuesAndComments() {
        var current = customizedConfig();

        ConfiguredConfigPatch.apply(current, emptyConfig());

        assertCustomValues(current);
        assertEquals("Keep this custom limit", current.getComment(
                "crafting.max_crafting_order_amount"));
    }

    @Test
    void shallowPutAllReproducesTheConfiguredBugBeforeSchemaCorrection() {
        var current = customizedConfig();
        current.set(PROGRESS, true);
        current.set(PLANNER, true);
        var changes = emptyConfig();
        changes.set(MATERIALS, true);
        changes.set(DIAGNOSTICS, true);

        current.putAll(changes);
        assertFalse(current.contains(PROGRESS));
        assertFalse(current.contains(PLANNER));
        Config.SPEC.correct(current);

        assertEquals(false, current.get(PROGRESS));
        assertEquals(false, current.get(PLANNER));
        assertEquals(true, current.get(MATERIALS));
        assertEquals(true, current.get(DIAGNOSTICS));
    }

    private static SynchronizedConfig emptyConfig() {
        return new SynchronizedConfig(TomlFormat.instance(), HashMap::new);
    }

    private static CommentedConfig customizedConfig() {
        var config = emptyConfig();
        Config.SPEC.correct(config);
        config.set("crafting.max_crafting_order_amount", 9_999_999_999L);
        config.setComment("crafting.max_crafting_order_amount", "Keep this custom limit");
        config.set("crafting.aelis.max_nodes", 654321);
        config.set("crafting.aelis.compile_budget_ms", 4321);
        config.set("crafting.aelis.cycle_solver.max_scc_nodes", 512);
        config.set("crafting.aelis.cycle_solver.seed_policy", "MAX_THROUGHPUT");
        config.set("performance.pattern_cache.max_entries_per_pattern", 64);
        return config;
    }

    private static void assertAllEnabled(CommentedConfig config) {
        for (String key : List.of(PROGRESS, MATERIALS, PLANNER, DIAGNOSTICS)) {
            assertEquals(true, config.get(key), key);
        }
        assertTrue(Config.SPEC.isCorrect(config));
    }

    private static void assertCustomValues(CommentedConfig config) {
        assertEquals(9_999_999_999L, (Long) config.get("crafting.max_crafting_order_amount"));
        assertEquals(654321, (Integer) config.get("crafting.aelis.max_nodes"));
        assertEquals(4321, (Integer) config.get("crafting.aelis.compile_budget_ms"));
        assertEquals(512, (Integer) config.get("crafting.aelis.cycle_solver.max_scc_nodes"));
        assertEquals("MAX_THROUGHPUT", config.get("crafting.aelis.cycle_solver.seed_policy"));
        assertEquals(64, (Integer) config.get("performance.pattern_cache.max_entries_per_pattern"));
    }
}
