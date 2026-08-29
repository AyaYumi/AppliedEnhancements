package com.appliedenhancements.config;

import com.appliedenhancements.AppliedEnhancements;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Migrates the former split configuration into the unified functional layout. */
public final class ConfigFileMigration {
    public static final String COMMON_FILE = "appliedenhancements-common.toml";
    public static final String LEGACY_MAX_FAST_FILE = "appliedenhancements-maxfast.toml";

    private static final String NEW_LAYOUT_PROBE =
            "crafting.max_fast.enable_automatic_planner";

    private ConfigFileMigration() {
    }

    public static void migrate(Path configDirectory) {
        Path commonPath = configDirectory.resolve(COMMON_FILE);
        Path legacyMaxFastPath = configDirectory.resolve(LEGACY_MAX_FAST_FILE);
        if (!Files.exists(commonPath) && !Files.exists(legacyMaxFastPath)) {
            return;
        }

        try {
            Files.createDirectories(configDirectory);
            migrateFiles(commonPath, legacyMaxFastPath);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to migrate Applied Enhancements configuration; original files were preserved",
                    exception);
        }
    }

    private static void migrateFiles(Path commonPath, Path legacyMaxFastPath)
            throws IOException {
        try (CommentedFileConfig common = openIfExists(commonPath);
                CommentedFileConfig legacyMaxFast = openIfExists(legacyMaxFastPath)) {
            if (common != null && common.contains(NEW_LAYOUT_PROBE)) {
                if (legacyMaxFast != null) {
                    legacyMaxFast.close();
                    archiveLegacyFile(legacyMaxFastPath);
                }
                return;
            }

            MigratedValues values = new MigratedValues(
                    readBoolean(common, "crafting.enable_long_range_crafting",
                            readBoolean(legacyMaxFast, "features.enableLongRangeCrafting", true)),
                    clamp(readLong(common, "crafting.max_crafting_order_amount",
                            Integer.MAX_VALUE), 1, Long.MAX_VALUE),
                    readBoolean(common, "crafting.enable_progress_display",
                            readBoolean(legacyMaxFast, "features.enableProgressDisplay", false)),
                    readBoolean(common, "crafting.enable_enhanced_material_calculation",
                            readBoolean(common,
                                    "crafting_plan.enable_enhanced_material_calculation", false)),
                    readBoolean(common, "crafting.max_fast.enable_automatic_planner",
                            readBoolean(legacyMaxFast,
                                    "maxfast.enableAutomaticMaxFastPlanner", false)),
                    (int) clamp(readLong(common, "crafting.max_fast.max_nodes",
                            readLong(legacyMaxFast, "maxfast.maxFastMaxNodes", 100000)),
                            1000, 1000000),
                    (int) clamp(readLong(common, "crafting.max_fast.compile_budget_ms",
                            readLong(legacyMaxFast,
                                    "maxfast.maxFastCompileBudgetMs", 2000)),
                            100, 30000),
                    readBoolean(common, "crafting.max_fast.enable_diagnostics",
                            readBoolean(legacyMaxFast, "debug.maxFastDiagnostics", false)),
                    readBoolean(common, "performance.pattern_cache.enabled",
                            readBoolean(common, "caching.enable_pattern_caching", true)),
                    (int) clamp(readLong(common,
                            "performance.pattern_cache.max_entries_per_pattern",
                            readLong(common, "caching.pattern_cache_size", 32)), 8, 256),
                    readBoolean(common, "performance.storage_bus.enable_slot_index",
                            readBoolean(common,
                                    "storage_bus.enable_storage_bus_slot_index", true)),
                    readBoolean(common, "performance.io_bus.enable_slot_routing",
                            readBoolean(common, "io_bus.enable_io_bus_optimization", true)),
                    readBoolean(common, "storage.infinite.enable_listing_limit_bypass",
                            readBoolean(common,
                                    "storage_bus.enable_infinite_storage_limit_bypass", false)));

            if (common != null) {
                common.close();
                Files.copy(
                        commonPath,
                        nextBackupPath(commonPath, ".pre-unified.bak"),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
            if (legacyMaxFast != null) {
                legacyMaxFast.close();
            }

            writeUnifiedConfig(commonPath, values);
            archiveLegacyFile(legacyMaxFastPath);
            AppliedEnhancements.LOGGER.info(
                    "Migrated Applied Enhancements configuration to unified file {}",
                    commonPath);
        }
    }

    private static void writeUnifiedConfig(Path commonPath, MigratedValues values)
            throws IOException {
        if (!Files.exists(commonPath)) {
            Files.createFile(commonPath);
        }
        try (CommentedFileConfig output = CommentedFileConfig.builder(commonPath)
                .sync()
                .build()) {
            output.load();
            output.clear();
            output.set("crafting.enable_long_range_crafting", values.longRangeCrafting());
            output.set("crafting.max_crafting_order_amount", values.maximumCraftingOrder());
            output.set("crafting.enable_progress_display", values.progressDisplay());
            output.set("crafting.enable_enhanced_material_calculation",
                    values.enhancedMaterialCalculation());
            output.set("crafting.max_fast.enable_automatic_planner",
                    values.automaticMaxFast());
            output.set("crafting.max_fast.max_nodes", values.maxFastMaxNodes());
            output.set("crafting.max_fast.compile_budget_ms",
                    values.maxFastCompileBudgetMs());
            output.set("crafting.max_fast.enable_diagnostics", values.maxFastDiagnostics());
            output.set("performance.pattern_cache.enabled", values.patternCaching());
            output.set("performance.pattern_cache.max_entries_per_pattern",
                    values.patternCacheSize());
            output.set("performance.storage_bus.enable_slot_index",
                    values.storageBusSlotIndex());
            output.set("performance.io_bus.enable_slot_routing", values.ioBusOptimization());
            output.set("storage.infinite.enable_listing_limit_bypass",
                    values.infiniteStorageLimitBypass());
            output.save();
        }
    }

    private static CommentedFileConfig openIfExists(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build();
        config.load();
        return config;
    }

    private static boolean readBoolean(
            CommentedConfig config, String path, boolean fallback) {
        if (config == null) {
            return fallback;
        }
        Object value = config.get(path);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static long readLong(CommentedConfig config, String path, long fallback) {
        if (config == null) {
            return fallback;
        }
        Object value = config.get(path);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void archiveLegacyFile(Path legacyPath) throws IOException {
        if (!Files.exists(legacyPath)) {
            return;
        }
        Files.move(
                legacyPath,
                nextBackupPath(legacyPath, ".migrated.bak"));
    }

    private static Path nextBackupPath(Path source, String suffix) {
        Path candidate = source.resolveSibling(source.getFileName() + suffix);
        int index = 1;
        while (Files.exists(candidate)) {
            candidate = source.resolveSibling(source.getFileName() + suffix + '.' + index++);
        }
        return candidate;
    }

    private record MigratedValues(
            boolean longRangeCrafting,
            long maximumCraftingOrder,
            boolean progressDisplay,
            boolean enhancedMaterialCalculation,
            boolean automaticMaxFast,
            int maxFastMaxNodes,
            int maxFastCompileBudgetMs,
            boolean maxFastDiagnostics,
            boolean patternCaching,
            int patternCacheSize,
            boolean storageBusSlotIndex,
            boolean ioBusOptimization,
            boolean infiniteStorageLimitBypass) {
    }
}
