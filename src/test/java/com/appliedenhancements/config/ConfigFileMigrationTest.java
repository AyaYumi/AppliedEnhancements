package com.appliedenhancements.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileMigrationTest {
    @TempDir
    Path configDirectory;

    @Test
    void migratesActiveMaxFastSectionToAelisWithoutLosingValues() throws Exception {
        Path common = configDirectory.resolve(ConfigFileMigration.COMMON_FILE);
        Files.writeString(common, """
                [crafting.max_fast]
                enable_automatic_planner = true
                max_nodes = 654321
                compile_budget_ms = 4321
                enable_diagnostics = false

                [crafting.max_fast.cycle_solver]
                max_scc_nodes = 512
                max_search_states = 2000000
                budget_ms = 2500
                """);

        ConfigFileMigration.migrate(configDirectory);

        try (CommentedFileConfig migrated = CommentedFileConfig.builder(common)
                .sync()
                .build()) {
            migrated.load();
            assertEquals(true, migrated.get("crafting.aelis.enable_automatic_planner"));
            assertEquals(654321,
                    ((Number) migrated.get("crafting.aelis.max_nodes")).intValue());
            assertEquals(4321,
                    ((Number) migrated.get("crafting.aelis.compile_budget_ms")).intValue());
            assertEquals(false, migrated.get("crafting.aelis.enable_diagnostics"));
            assertEquals(512, ((Number) migrated.get(
                    "crafting.aelis.cycle_solver.max_scc_nodes")).intValue());
            assertEquals(2_000_000, ((Number) migrated.get(
                    "crafting.aelis.cycle_solver.max_search_states")).intValue());
            assertEquals(2500, ((Number) migrated.get(
                    "crafting.aelis.cycle_solver.budget_ms")).intValue());
            assertEquals("PRESERVE_MINIMUM", migrated.get(
                    "crafting.aelis.cycle_solver.seed_policy"));
            assertFalse(migrated.contains("crafting.max_fast"));
        }
    }

    @Test
    void mergesSplitLegacyFilesWithoutLosingCustomizedValues() throws Exception {
        Path common = configDirectory.resolve(ConfigFileMigration.COMMON_FILE);
        Path legacy = configDirectory.resolve(ConfigFileMigration.LEGACY_PRE_AELIS_FILE);
        Files.writeString(common, """
                [crafting]
                max_crafting_order_amount = 9999999999

                [caching]
                enable_pattern_caching = false
                pattern_cache_size = 128

                [crafting_plan]
                enable_enhanced_material_calculation = false

                [storage_bus]
                enable_storage_bus_slot_index = false
                enable_infinite_storage_limit_bypass = false

                [io_bus]
                enable_io_bus_optimization = false
                """);
        Files.writeString(legacy, """
                [features]
                enableLongRangeCrafting = false
                enableProgressDisplay = false

                [maxfast]
                enableAutomaticMaxFastPlanner = true
                maxFastMaxNodes = 456789
                maxFastCompileBudgetMs = 12345

                [debug]
                maxFastDiagnostics = true
                """);

        ConfigFileMigration.migrate(configDirectory);

        try (CommentedFileConfig migrated = CommentedFileConfig.builder(common)
                .sync()
                .build()) {
            migrated.load();
            assertEquals(false, migrated.get("crafting.enable_long_range_crafting"));
            assertEquals(9999999999L,
                    ((Number) migrated.get("crafting.max_crafting_order_amount")).longValue());
            assertEquals(false, migrated.get("crafting.enable_progress_display"));
            assertEquals(false,
                    migrated.get("crafting.enable_enhanced_material_calculation"));
            assertEquals(true,
                    migrated.get("crafting.aelis.enable_automatic_planner"));
            assertEquals(456789,
                    ((Number) migrated.get("crafting.aelis.max_nodes")).intValue());
            assertEquals(12345,
                    ((Number) migrated.get("crafting.aelis.compile_budget_ms")).intValue());
            assertEquals(256, ((Number) migrated.get(
                    "crafting.aelis.cycle_solver.max_scc_nodes")).intValue());
            assertEquals(1_000_000, ((Number) migrated.get(
                    "crafting.aelis.cycle_solver.max_search_states")).intValue());
            assertEquals(1000, ((Number) migrated.get(
                    "crafting.aelis.cycle_solver.budget_ms")).intValue());
            assertEquals("PRESERVE_MINIMUM", migrated.get(
                    "crafting.aelis.cycle_solver.seed_policy"));
            assertEquals(true, migrated.get("crafting.aelis.enable_diagnostics"));
            assertEquals(false, migrated.get("performance.pattern_cache.enabled"));
            assertEquals(128, ((Number) migrated.get(
                    "performance.pattern_cache.max_entries_per_pattern")).intValue());
            assertEquals(false,
                    migrated.get("performance.storage_bus.enable_slot_index"));
            assertEquals(false,
                    migrated.get("performance.io_bus.enable_slot_routing"));
            assertEquals(false,
                    migrated.get("storage.infinite.enable_listing_limit_bypass"));

            assertFalse(migrated.contains("caching.enable_pattern_caching"));
            assertFalse(migrated.contains("crafting_plan.enable_enhanced_material_calculation"));
            assertFalse(migrated.contains("storage_bus.enable_storage_bus_slot_index"));
        }

        assertFalse(Files.exists(legacy));
        assertEquals(1, countFilesContaining("appliedenhancements-common.toml.pre-aelis.bak"));
        assertEquals(1, countFilesContaining("appliedenhancements-maxfast.toml.migrated.bak"));

        ConfigFileMigration.migrate(configDirectory);
        assertEquals(1, countFilesContaining("appliedenhancements-common.toml.pre-aelis.bak"));
        assertEquals(1, countFilesContaining("appliedenhancements-maxfast.toml.migrated.bak"));
    }

    @Test
    void createsUnifiedFileWhenOnlyLegacyMaxFastConfigExists() throws Exception {
        Path legacy = configDirectory.resolve(ConfigFileMigration.LEGACY_PRE_AELIS_FILE);
        Files.writeString(legacy, """
                [features]
                enableLongRangeCrafting = true
                enableProgressDisplay = false

                [maxfast]
                enableAutomaticMaxFastPlanner = true
                maxFastMaxNodes = 100000
                maxFastCompileBudgetMs = 2000

                [debug]
                maxFastDiagnostics = false
                """);

        ConfigFileMigration.migrate(configDirectory);

        Path common = configDirectory.resolve(ConfigFileMigration.COMMON_FILE);
        assertTrue(Files.exists(common));
        assertFalse(Files.exists(legacy));
        try (CommentedFileConfig migrated = CommentedFileConfig.builder(common)
                .sync()
                .build()) {
            migrated.load();
            assertEquals(true, migrated.get("crafting.enable_long_range_crafting"));
            assertEquals(false, migrated.get("crafting.enable_progress_display"));
            assertEquals(true,
                    migrated.get("crafting.aelis.enable_automatic_planner"));
            assertEquals(true, migrated.get("performance.pattern_cache.enabled"));
            assertEquals(false,
                    migrated.get("storage.infinite.enable_listing_limit_bypass"));
        }
    }

    private long countFilesContaining(String fragment) throws Exception {
        try (var paths = Files.list(configDirectory)) {
            return paths.filter(path -> path.getFileName().toString().contains(fragment)).count();
        }
    }
}
