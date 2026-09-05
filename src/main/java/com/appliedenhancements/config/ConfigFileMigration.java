package com.appliedenhancements.config;

import com.appliedenhancements.AppliedEnhancements;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Migrates pre-AELIS configuration layouts into the current functional layout. */
public final class ConfigFileMigration {
    public static final String COMMON_FILE = "appliedenhancements-common.toml";
    public static final String LEGACY_PRE_AELIS_FILE = "appliedenhancements-maxfast.toml";

    private static final String NEW_LAYOUT_PROBE =
            "crafting.aelis.enable_automatic_planner";
    private static final String PRE_AELIS_LAYOUT_PROBE =
            "crafting.max_fast.enable_automatic_planner";

    private ConfigFileMigration() {
    }

    public static void migrate(Path configDirectory) {
        Path commonPath = configDirectory.resolve(COMMON_FILE);
        Path legacyPreAelisPath = configDirectory.resolve(LEGACY_PRE_AELIS_FILE);
        if (!Files.exists(commonPath) && !Files.exists(legacyPreAelisPath)) {
            return;
        }

        try {
            Files.createDirectories(configDirectory);
            migrateFiles(commonPath, legacyPreAelisPath);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to migrate Applied Enhancements configuration; original files were preserved",
                    exception);
        }
    }

    private static void migrateFiles(Path commonPath, Path legacyPreAelisPath)
            throws IOException {
        try (CommentedFileConfig common = openIfExists(commonPath);
                CommentedFileConfig legacyPreAelis = openIfExists(legacyPreAelisPath)) {
            if (common != null && common.contains(NEW_LAYOUT_PROBE)
                    && !common.contains(PRE_AELIS_LAYOUT_PROBE)) {
                if (legacyPreAelis != null) {
                    legacyPreAelis.close();
                    archiveLegacyFile(legacyPreAelisPath);
                }
                return;
            }

            MigratedValues values = new MigratedValues(
                    readBoolean(common, "crafting.enable_long_range_crafting",
                            readBoolean(legacyPreAelis,
                                    "features.enableLongRangeCrafting", true)),
                    clamp(readLong(common, "crafting.max_crafting_order_amount",
                            Integer.MAX_VALUE), 1, Long.MAX_VALUE),
                    readBoolean(common, "crafting.enable_progress_display",
                            readBoolean(legacyPreAelis,
                                    "features.enableProgressDisplay", false)),
                    readBoolean(common, "crafting.enable_enhanced_material_calculation",
                            readBoolean(common,
                                    "crafting_plan.enable_enhanced_material_calculation", false)),
                    readBoolean(common, "crafting.aelis.enable_automatic_planner",
                            readBoolean(common,
                                    "crafting.max_fast.enable_automatic_planner",
                                    readBoolean(legacyPreAelis,
                                            "maxfast.enableAutomaticMaxFastPlanner", false))),
                    (int) clamp(readLong(common, "crafting.aelis.max_nodes",
                            readLong(common, "crafting.max_fast.max_nodes",
                                    readLong(legacyPreAelis,
                                            "maxfast.maxFastMaxNodes", 100000))),
                            1000, 1000000),
                    (int) clamp(readLong(common, "crafting.aelis.compile_budget_ms",
                            readLong(common, "crafting.max_fast.compile_budget_ms",
                                    readLong(legacyPreAelis,
                                            "maxfast.maxFastCompileBudgetMs", 2000))),
                            100, 30000),
                    (int) clamp(readLong(common,
                            "crafting.aelis.cycle_solver.max_scc_nodes",
                            readLong(common,
                                    "crafting.max_fast.cycle_solver.max_scc_nodes", 256)),
                            4, 1024),
                    (int) clamp(readLong(common,
                            "crafting.aelis.cycle_solver.max_search_states",
                            readLong(common,
                                    "crafting.max_fast.cycle_solver.max_search_states",
                                    1_000_000)),
                            1_000, 10_000_000),
                    (int) clamp(readLong(common,
                            "crafting.aelis.cycle_solver.budget_ms",
                            readLong(common,
                                    "crafting.max_fast.cycle_solver.budget_ms", 1000)),
                            10, 5000),
                    readString(common,
                            "crafting.aelis.cycle_solver.seed_policy",
                            "PRESERVE_MINIMUM"),
                    readBoolean(common, "crafting.aelis.enable_diagnostics",
                            readBoolean(common, "crafting.max_fast.enable_diagnostics",
                                    readBoolean(legacyPreAelis,
                                            "debug.maxFastDiagnostics", false))),
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
                        nextBackupPath(commonPath, ".pre-aelis.bak"),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
            if (legacyPreAelis != null) {
                legacyPreAelis.close();
            }

            writeUnifiedConfig(commonPath, values);
            archiveLegacyFile(legacyPreAelisPath);
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
            output.set("crafting.aelis.enable_automatic_planner",
                    values.automaticAelis());
            output.set("crafting.aelis.max_nodes", values.aelisMaxNodes());
            output.set("crafting.aelis.compile_budget_ms",
                    values.aelisCompileBudgetMs());
            output.set("crafting.aelis.cycle_solver.max_scc_nodes",
                    values.cycleSolverMaxSccNodes());
            output.set("crafting.aelis.cycle_solver.max_search_states",
                    values.cycleSolverMaxSearchStates());
            output.set("crafting.aelis.cycle_solver.budget_ms",
                    values.cycleSolverBudgetMs());
            output.set("crafting.aelis.cycle_solver.seed_policy",
                    values.cycleSeedPolicy());
            output.set("crafting.aelis.enable_diagnostics", values.aelisDiagnostics());
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

    private static String readString(
            CommentedConfig config, String path, String fallback) {
        if (config == null) {
            return fallback;
        }
        Object value = config.get(path);
        return value instanceof String string && !string.isBlank()
                ? string
                : fallback;
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
            boolean automaticAelis,
            int aelisMaxNodes,
            int aelisCompileBudgetMs,
            int cycleSolverMaxSccNodes,
            int cycleSolverMaxSearchStates,
            int cycleSolverBudgetMs,
            String cycleSeedPolicy,
            boolean aelisDiagnostics,
            boolean patternCaching,
            int patternCacheSize,
            boolean storageBusSlotIndex,
            boolean ioBusOptimization,
            boolean infiniteStorageLimitBypass) {
    }
}
