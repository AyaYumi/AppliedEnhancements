package com.github.appliedenhancements.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class AppliedEnhancementsConfig {
    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;

    static {
        Pair<Common, ModConfigSpec> commonPair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = commonPair.getLeft();
        COMMON_SPEC = commonPair.getRight();
    }

    public static class Common {
        public final ModConfigSpec.BooleanValue enableLongRangeCrafting;
        public final ModConfigSpec.BooleanValue enableProgressDisplay;
        public final ModConfigSpec.BooleanValue enableAutomaticMaxFastPlanner;
        public final ModConfigSpec.IntValue maxFastMaxNodes;
        public final ModConfigSpec.IntValue maxFastCompileBudgetMs;
        public final ModConfigSpec.BooleanValue maxFastDiagnostics;
        public Common(ModConfigSpec.Builder builder) {
            builder.push("features");

            enableLongRangeCrafting = builder
                    .comment("Enable long-range crafting (up to Long.MAX_VALUE)")
                    .define("enableLongRangeCrafting", true);

            enableProgressDisplay = builder
                    .comment("Enable crafting calculation progress display")
                    .define("enableProgressDisplay", true);

            builder.pop();

            builder.push("maxfast");

            enableAutomaticMaxFastPlanner = builder
                    .comment("Enable automatic MAX_FAST integration in AE2's native planner.",
                            "Disabled by default. Third-party API calls are not affected by this setting.",
                            "MAX_FAST uses its single aggressive execution policy when enabled.",
                            "Manual crafting-plan inventory reservations are active only while this is enabled.")
                    .define("enableAutomaticMaxFastPlanner", false);

            maxFastMaxNodes = builder
                    .comment("Maximum nodes for MAX_FAST planner",
                            "Higher values allow more complex recipes but use more memory")
                    .defineInRange("maxFastMaxNodes", 100000, 1000, 1000000);

            maxFastCompileBudgetMs = builder
                    .comment("Compilation budget in milliseconds for MAX_FAST planner",
                            "Maximum time spent analyzing the crafting tree before execution")
                    .defineInRange("maxFastCompileBudgetMs", 2000, 100, 30000);

            builder.pop();

            builder.push("debug");

            maxFastDiagnostics = builder
                    .comment("Enable MAX_FAST diagnostic logging",
                            "When enabled, detailed information about planning decisions will be logged",
                            "WARNING: This will generate a LOT of log output!")
                    .define("maxFastDiagnostics", false);

            builder.pop();
        }
    }
}
