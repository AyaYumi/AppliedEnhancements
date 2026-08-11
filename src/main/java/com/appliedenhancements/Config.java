package com.appliedenhancements;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for Applied Enhancements.
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.LongValue MAX_CRAFTING_ORDER_AMOUNT;
    public static final ModConfigSpec.BooleanValue ENABLE_PATTERN_CACHING;
    public static final ModConfigSpec.IntValue PATTERN_CACHE_SIZE;
    public static final ModConfigSpec.BooleanValue ENABLE_ENHANCED_MATERIAL_CALCULATION;

    static {
        var builder = new ModConfigSpec.Builder();

        builder.comment("Long-range crafting settings").push("crafting");

        MAX_CRAFTING_ORDER_AMOUNT = builder
                .comment("Maximum amount allowed for a single AE2 autocrafting order.",
                        "Values above Integer.MAX_VALUE (2,147,483,647) use long-range crafting.",
                        "Default: 1 trillion")
                .translation("appliedenhancements.config.max_crafting_order_amount")
                .defineInRange("max_crafting_order_amount", 1_000_000_000_000L, 1L, Long.MAX_VALUE);

        builder.pop();

        builder.comment("Pattern caching settings").push("caching");

        ENABLE_PATTERN_CACHING = builder
                .comment("Enable pattern input validation and container item caching.",
                        "Significantly improves performance during large crafting calculations.",
                        "Default: true")
                .translation("appliedenhancements.config.enable_pattern_caching")
                .define("enable_pattern_caching", true);

        PATTERN_CACHE_SIZE = builder
                .comment("Maximum entries in multi-key pattern cache per pattern.",
                        "Prevents memory leaks while allowing reasonable cache diversity.",
                        "Default: 32")
                .translation("appliedenhancements.config.pattern_cache_size")
                .defineInRange("pattern_cache_size", 32, 8, 256);

        builder.pop();

        builder.comment("Crafting plan enhancement settings").push("crafting_plan");

        ENABLE_ENHANCED_MATERIAL_CALCULATION = builder
                .comment("Enable enhanced material calculation in crafting preview.",
                        "Shows accurate missing/stored/crafting item counts.",
                        "Default: true")
                .translation("appliedenhancements.config.enable_enhanced_material_calculation")
                .define("enable_enhanced_material_calculation", true);

        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
