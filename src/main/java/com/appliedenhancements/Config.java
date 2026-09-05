package com.appliedenhancements;

import com.appliedenhancements.api.AelisCycleSeedPolicy;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Unified configuration for Applied Enhancements. */
public final class Config {
    public static final ModConfigSpec SPEC;

    // Crafting
    public static final ModConfigSpec.BooleanValue ENABLE_LONG_RANGE_CRAFTING;
    public static final ModConfigSpec.LongValue MAX_CRAFTING_ORDER_AMOUNT;
    public static final ModConfigSpec.BooleanValue ENABLE_PROGRESS_DISPLAY;
    public static final ModConfigSpec.BooleanValue ENABLE_ENHANCED_MATERIAL_CALCULATION;

    // Crafting / AELIS
    public static final ModConfigSpec.BooleanValue ENABLE_AUTOMATIC_AELIS_PLANNER;
    public static final ModConfigSpec.IntValue AELIS_MAX_NODES;
    public static final ModConfigSpec.IntValue AELIS_COMPILE_BUDGET_MS;
    public static final ModConfigSpec.BooleanValue AELIS_DIAGNOSTICS;
    public static final ModConfigSpec.IntValue CYCLE_SOLVER_MAX_SCC_NODES;
    public static final ModConfigSpec.IntValue CYCLE_SOLVER_MAX_SEARCH_STATES;
    public static final ModConfigSpec.IntValue CYCLE_SOLVER_BUDGET_MS;
    public static final ModConfigSpec.EnumValue<AelisCycleSeedPolicy> CYCLE_SEED_POLICY;

    // Performance
    public static final ModConfigSpec.BooleanValue ENABLE_PATTERN_CACHING;
    public static final ModConfigSpec.IntValue PATTERN_CACHE_SIZE;
    public static final ModConfigSpec.BooleanValue ENABLE_STORAGE_BUS_SLOT_INDEX;
    public static final ModConfigSpec.BooleanValue ENABLE_IO_BUS_OPTIMIZATION;

    // Storage
    public static final ModConfigSpec.BooleanValue ENABLE_INFINITE_STORAGE_LIMIT_BYPASS;

    static {
        var builder = new ModConfigSpec.Builder();

        builder.comment(
                "AE2 crafting behavior and presentation",
                "AE2 合成行为与界面显示")
                .translation("appliedenhancements.config.section.crafting")
                .push("crafting");

        ENABLE_LONG_RANGE_CRAFTING = builder
                .comment(
                        "Enable crafting orders above Integer.MAX_VALUE, up to Long.MAX_VALUE.",
                        "启用超过 Integer.MAX_VALUE、最大可到 Long.MAX_VALUE 的合成订单。",
                        "Default / 默认值: true")
                .translation("appliedenhancements.config.enable_long_range_crafting")
                .define("enable_long_range_crafting", true);

        MAX_CRAFTING_ORDER_AMOUNT = builder
                .comment(
                        "Maximum amount allowed for a single AE2 autocrafting order.",
                        "Values above Integer.MAX_VALUE (2,147,483,647) use long-range crafting.",
                        "单次 AE2 自动合成订单允许的最大数量。",
                        "超过 Integer.MAX_VALUE（2,147,483,647）时使用超大数量合成路径。",
                        "Default / 默认值: Integer.MAX_VALUE (2,147,483,647)")
                .translation("appliedenhancements.config.max_crafting_order_amount")
                .defineInRange(
                        "max_crafting_order_amount",
                        (long) Integer.MAX_VALUE,
                        1L,
                        Long.MAX_VALUE);

        ENABLE_PROGRESS_DISPLAY = builder
                .comment(
                        "Show crafting calculation progress and selected planner path.",
                        "显示合成计算进度与所选规划路径。",
                        "Default / 默认值: false")
                .translation("appliedenhancements.config.enable_progress_display")
                .define("enable_progress_display", false);

        ENABLE_ENHANCED_MATERIAL_CALCULATION = builder
                .comment(
                        "Enable enhanced material calculation in the crafting preview.",
                        "Shows accurate missing, stored, and crafting item counts.",
                        "在合成预览中启用增强材料计算。",
                        "显示准确的缺失、库存与待合成物品数量。",
                        "Default / 默认值: false")
                .translation("appliedenhancements.config.enable_enhanced_material_calculation")
                .define("enable_enhanced_material_calculation", false);

        builder.comment(
                "AELIS planner settings",
                "AELIS 规划器设置")
                .translation("appliedenhancements.config.section.aelis")
                .push("aelis");

        ENABLE_AUTOMATIC_AELIS_PLANNER = builder
                .comment(
                        "Enable automatic AELIS integration in AE2's native planner.",
                        "Third-party API calls are not affected by this setting.",
                        "Manual crafting-plan inventory reservations are active only while this is enabled.",
                        "在 AE2 原生规划流程中自动启用 AELIS。",
                        "第三方 API 调用不受此设置影响。",
                        "手动合成计划库存预留仅在此项启用时生效。",
                        "Default / 默认值: false")
                .translation("appliedenhancements.config.enable_automatic_aelis_planner")
                .define("enable_automatic_planner", false);

        AELIS_MAX_NODES = builder
                .comment(
                        "Maximum nodes analyzed by AELIS per attempt.",
                        "Higher values allow more complex recipes but use more memory.",
                        "AELIS 每次尝试允许分析的最大节点数。",
                        "数值越高，可处理的配方越复杂，但会占用更多内存。",
                        "Default / 默认值: 100000")
                .translation("appliedenhancements.config.aelis_max_nodes")
                .defineInRange("max_nodes", 100000, 1000, 1000000);

        AELIS_COMPILE_BUDGET_MS = builder
                .comment(
                        "Compilation budget in milliseconds for each AELIS attempt.",
                        "Limits time spent analyzing the crafting tree before execution.",
                        "每次 AELIS 尝试的编译时间预算，单位为毫秒。",
                        "限制执行前分析合成树所花费的最长时间。",
                        "Default / 默认值: 2000")
                .translation("appliedenhancements.config.aelis_compile_budget_ms")
                .defineInRange("compile_budget_ms", 2000, 100, 30000);

        AELIS_DIAGNOSTICS = builder
                .comment(
                        "Enable detailed AELIS planning diagnostics.",
                        "WARNING: This produces a large amount of log output.",
                        "启用 AELIS 规划诊断详情。",
                        "警告：这会产生大量日志输出。",
                        "Default / 默认值: false")
                .translation("appliedenhancements.config.aelis_diagnostics")
                .define("enable_diagnostics", false);

        builder.comment(
                "Bounded solver for cyclic crafting dependencies",
                "循环合成依赖的有界求解器")
                .translation("appliedenhancements.config.section.cycle_solver")
                .push("cycle_solver");

        CYCLE_SOLVER_MAX_SCC_NODES = builder
                .comment(
                        "Maximum material nodes in one strongly connected component.",
                        "单个强连通分量允许包含的最大材料节点数。",
                        "Default / 默认值: 256")
                .translation("appliedenhancements.config.cycle_solver_max_scc_nodes")
                .defineInRange("max_scc_nodes", 256, 4, 1024);

        CYCLE_SOLVER_MAX_SEARCH_STATES = builder
                .comment(
                        "Maximum branch-search states for multi-candidate cyclic components.",
                        "多候选循环分量允许搜索的最大分支状态数。",
                        "Default / 默认值: 1000000")
                .translation("appliedenhancements.config.cycle_solver_max_search_states")
                .defineInRange("max_search_states", 1_000_000, 1_000, 10_000_000);

        CYCLE_SOLVER_BUDGET_MS = builder
                .comment(
                        "Maximum time spent by one global or local cyclic solve in milliseconds.",
                        "单次全图或局部循环求解允许使用的最大时间（毫秒）。",
                        "Default / 默认值: 1000")
                .translation("appliedenhancements.config.cycle_solver_budget_ms")
                .defineInRange("budget_ms", 1000, 10, 5000);

        CYCLE_SEED_POLICY = builder
                .comment(
                        "Policy for cycle startup materials after the proven cycle schedule finishes.",
                        "PRESERVE_MINIMUM keeps the minimum seed vector for the next order.",
                        "MAX_THROUGHPUT allows the current order to consume every cycle material.",
                        "已证明循环顺序执行完成后，启动材料的处理策略。",
                        "PRESERVE_MINIMUM 会为下一份订单保留最低种子向量。",
                        "MAX_THROUGHPUT 允许当前订单使用全部循环材料。",
                        "Default / 默认值: PRESERVE_MINIMUM")
                .translation("appliedenhancements.config.cycle_seed_policy")
                .defineEnum("seed_policy", AelisCycleSeedPolicy.PRESERVE_MINIMUM);

        builder.pop();

        builder.pop();
        builder.pop();

        builder.comment(
                "Performance optimizations with validation and native fallbacks",
                "带重新验证与原版回退的性能优化")
                .translation("appliedenhancements.config.section.performance")
                .push("performance");

        builder.comment(
                "Encoded-pattern cache settings",
                "编码样板缓存设置")
                .translation("appliedenhancements.config.section.pattern_cache")
                .push("pattern_cache");

        ENABLE_PATTERN_CACHING = builder
                .comment(
                        "Cache pattern input validation and container-return lookups.",
                        "Significantly improves large crafting calculations.",
                        "缓存样板输入有效性与容器返还物查询。",
                        "可显著提升大型合成计算的性能。",
                        "Default / 默认值: true")
                .translation("appliedenhancements.config.enable_pattern_caching")
                .define("enabled", true);

        PATTERN_CACHE_SIZE = builder
                .comment(
                        "Maximum multi-key cache entries retained per pattern.",
                        "Limits memory use while preserving useful cache diversity.",
                        "每张样板保留的多键缓存最大条目数。",
                        "在保留有效缓存多样性的同时限制内存占用。",
                        "Default / 默认值: 32")
                .translation("appliedenhancements.config.pattern_cache_size")
                .defineInRange("max_entries_per_pattern", 32, 8, 256);

        builder.pop();

        builder.comment(
                "Item storage-bus optimization",
                "物品存储总线优化")
                .translation("appliedenhancements.config.section.storage_bus")
                .push("storage_bus");

        ENABLE_STORAGE_BUS_SLOT_INDEX = builder
                .comment(
                        "Index candidate slots during AE2's existing external-inventory scan.",
                        "Cached slots are revalidated; incomplete results fall back to AE2's full scan.",
                        "在 AE2 原有外部库存扫描期间建立候选槽位索引。",
                        "缓存槽位会重新验证；结果不完整时回退 AE2 完整扫描。",
                        "Default / 默认值: true")
                .translation("appliedenhancements.config.enable_storage_bus_slot_index")
                .define("enable_slot_index", true);

        builder.pop();

        builder.comment(
                "Import and export bus optimization",
                "输入与输出总线优化")
                .translation("appliedenhancements.config.section.io_bus")
                .push("io_bus");

        ENABLE_IO_BUS_OPTIMIZATION = builder
                .comment(
                        "Reuse validated source and target slot hints for AE2 I/O buses.",
                        "Incomplete operations fall back to AE2's original slot routing.",
                        "为 AE2 输入与输出总线复用经过验证的来源及目标槽位提示。",
                        "操作结果不完整时回退 AE2 原始槽位路由。",
                        "Default / 默认值: true")
                .translation("appliedenhancements.config.enable_io_bus_optimization")
                .define("enable_slot_routing", true);

        builder.pop();
        builder.pop();

        builder.comment(
                "Storage behavior and representation",
                "存储行为与显示")
                .translation("appliedenhancements.config.section.storage")
                .push("storage");

        builder.comment(
                "Explicitly marked infinite storage cells",
                "显式标记的无限存储元件")
                .translation("appliedenhancements.config.section.infinite")
                .push("infinite");

        ENABLE_INFINITE_STORAGE_LIMIT_BYPASS = builder
                .comment(
                        "Raise infinite-cell network listings to Long.MAX_VALUE and display them as 9.2E.",
                        "Disable this to preserve the amount reported by the original cell implementation.",
                        "Long.MAX_VALUE can leave no arithmetic headroom for AE2's native planner.",
                        "将无限磁盘网络数量提升至 Long.MAX_VALUE，并显示为 9.2E。",
                        "关闭后保留磁盘原实现报告的数量。",
                        "Long.MAX_VALUE 可能不给 AE2 原生规划器留下算术余量。",
                        "Default / 默认值: false")
                .translation("appliedenhancements.config.enable_infinite_storage_limit_bypass")
                .define("enable_listing_limit_bypass", false);

        builder.pop();
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
