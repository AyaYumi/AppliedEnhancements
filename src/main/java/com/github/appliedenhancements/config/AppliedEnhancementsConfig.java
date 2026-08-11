package com.github.appliedenhancements.config;

import com.github.appliedenhancements.crafting.maxfast.OmniMaxFastMode;
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
        public final ModConfigSpec.BooleanValue enableMaxFastPlanner;
        public final ModConfigSpec.EnumValue<OmniMaxFastMode> maxFastMode;
        public final ModConfigSpec.IntValue maxFastMaxNodes;
        public final ModConfigSpec.IntValue maxFastCompileBudgetMs;
        public final ModConfigSpec.IntValue maxFastPlannerPriority;
        public final ModConfigSpec.BooleanValue maxFastAutoYield;
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

            enableMaxFastPlanner = builder
                    .comment("Enable the optional MAX_FAST crafting planner")
                    .define("enableMaxFastPlanner", true);

            maxFastMode = builder
                    .comment("MAX_FAST planner mode",
                            "OFF: Disabled",
                            "SAFE: Conservative optimization (recommended)",
                            "AGGRESSIVE: Relaxes compatibility checks and may fail hard")
                    .defineEnum("maxFastMode", OmniMaxFastMode.SAFE);

            maxFastMaxNodes = builder
                    .comment("Maximum nodes for MAX_FAST planner",
                            "Higher values allow more complex recipes but use more memory")
                    .defineInRange("maxFastMaxNodes", 100000, 1000, 1000000);

            maxFastCompileBudgetMs = builder
                    .comment("Compilation budget in milliseconds for MAX_FAST planner",
                            "Maximum time spent analyzing the crafting tree before execution")
                    .defineInRange("maxFastCompileBudgetMs", 2000, 100, 30000);

            maxFastPlannerPriority = builder
                    .comment("MAX_FAST planner priority (lower = higher priority)",
                            "Set to a high value (e.g., 1000) to yield to other optimization mods like EcoAE",
                            "Set to a low value (e.g., 100) to take priority over other optimizers")
                    .defineInRange("maxFastPlannerPriority", 500, 0, 10000);

            maxFastAutoYield = builder
                    .comment("Automatically yield to EcoAE when it has higher priority")
                    .define("maxFastAutoYield", true);

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
