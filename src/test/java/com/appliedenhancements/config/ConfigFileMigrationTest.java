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
    void mergesSplitLegacyFilesWithoutLosingCustomizedValues() throws Exception {
        Path common = configDirectory.resolve(ConfigFileMigration.COMMON_FILE);
        Path maxFast = configDirectory.resolve(ConfigFileMigration.LEGACY_MAX_FAST_FILE);
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
        Files.writeString(maxFast, """
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
                    migrated.get("crafting.max_fast.enable_automatic_planner"));
            assertEquals(456789,
                    ((Number) migrated.get("crafting.max_fast.max_nodes")).intValue());
            assertEquals(12345,
                    ((Number) migrated.get("crafting.max_fast.compile_budget_ms")).intValue());
            assertEquals(true, migrated.get("crafting.max_fast.enable_diagnostics"));
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

        assertFalse(Files.exists(maxFast));
        assertEquals(1, countFilesContaining("appliedenhancements-common.toml.pre-unified.bak"));
        assertEquals(1, countFilesContaining("appliedenhancements-maxfast.toml.migrated.bak"));

        ConfigFileMigration.migrate(configDirectory);
        assertEquals(1, countFilesContaining("appliedenhancements-common.toml.pre-unified.bak"));
        assertEquals(1, countFilesContaining("appliedenhancements-maxfast.toml.migrated.bak"));
    }

    @Test
    void createsUnifiedFileWhenOnlyLegacyMaxFastConfigExists() throws Exception {
        Path maxFast = configDirectory.resolve(ConfigFileMigration.LEGACY_MAX_FAST_FILE);
        Files.writeString(maxFast, """
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
        assertFalse(Files.exists(maxFast));
        try (CommentedFileConfig migrated = CommentedFileConfig.builder(common)
                .sync()
                .build()) {
            migrated.load();
            assertEquals(true, migrated.get("crafting.enable_long_range_crafting"));
            assertEquals(false, migrated.get("crafting.enable_progress_display"));
            assertEquals(true,
                    migrated.get("crafting.max_fast.enable_automatic_planner"));
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
