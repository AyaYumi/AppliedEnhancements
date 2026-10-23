# Configuration / 配置参考

Java 17 / Minecraft 1.20.1 / Forge 47.

Applied Enhancements **1.1.4-forge** generates `config/appliedenhancements-common.toml`
in the active instance. Edit existing sections and restart both sides.
API integration: [English](docs/API_INTEGRATION.md) · [中文](docs/API_INTEGRATION_ZH.md)

| Path | Default | Behavior / range |
| --- | --- | --- |
| `crafting.max_crafting_order_amount` | DISABLED | DISABLED native; LONG_MAX long input; BIG_INTEGER exact input without the former 256-digit order limit |
| `crafting.enable_progress_display` | false | Calculation progress and planner-path display |
| `crafting.enable_enhanced_material_calculation` | false | Enhanced preview stock/crafting/missing statistics |
| `crafting.aelis.enable_automatic_planner` | false | Automatic AELIS interception and manual-plan stock reservations |
| `crafting.aelis.enable_plan_screen` | true | Local client AELIS custom confirmation screen; false keeps AE2's original view without changing planning or execution |
| `crafting.aelis.enable_big_integer_planning` | true | Exact automatic AELIS arithmetic preference; explicit exact service requests remain exact even when false |
| `crafting.aelis.max_nodes` | 100000 | 1000–1,000,000 nodes per attempt |
| `crafting.aelis.compile_budget_ms` | 2000 | 100–30000 ms per compile attempt |
| `crafting.aelis.enable_diagnostics` | false | Compatibility setting for older OmniSequence builds; this release emits no planning traces |
| `crafting.aelis.cycle_solver.max_scc_nodes` | 256 | 4–1024 material nodes per cyclic component |
| `crafting.aelis.cycle_solver.max_search_states` | 1000000 | 1000–10,000,000 branch-search states |
| `crafting.aelis.cycle_solver.budget_ms` | 1000 | 10–5000 ms per cyclic solve |
| `crafting.aelis.cycle_solver.seed_policy` | PRESERVE_MINIMUM | Preserve proven starter seed; MAX_THROUGHPUT uses available cycle stock |
| `performance.pattern_cache.enabled` | true | Pattern-input and container-return caching |
| `performance.pattern_cache.max_entries_per_pattern` | 32 | 8–256 retained entries per pattern |
| `performance.storage_bus.enable_slot_index` | true | Candidate-slot index for item storage buses |
| `performance.io_bus.enable_slot_routing` | true | Validated import/export routing hints |
| `storage.infinite.enable_listing_limit_bypass` | false | Explicitly marked infinite cells supply without finite listing limits |

The 18 options in 9 sections have localized names, tooltips and enum labels in English
and Simplified Chinese, including Configured Forge 2.2.3. TOML comments document
both languages, numeric bounds and allowed values. Stored identifiers remain stable:
`DISABLED`, `LONG_MAX`, `BIG_INTEGER`, `PRESERVE_MINIMUM`, `MAX_THROUGHPUT`.

## Planning and reservations

The AELIS custom confirmation screen defaults on. To disable it, set
`enable_plan_screen = false` in the existing `[crafting.aelis]` section of the
client's `config/appliedenhancements-common.toml`. The UI translation key is
`appliedenhancements.config.enable_aelis_plan_screen` (`enable_aelis_plan_screen`),
not a separate TOML option. Once the changed configuration is loaded, an open
AELIS screen also returns to the original AE2 view. This is a local presentation
preference, not a server-controlled planner or crafting switch. The enabled screen
opens directly after quantity selection and displays calculation/replanning progress;
completed non-AELIS results use the native view. Detailed phases follow the server's
progress-display setting; a waiting animation works even when it is disabled.

Materials and Craft Tree show the same plan. The graph loads on demand and is bounded
to 512 material nodes and 2048 edges, without a separate setting. Dragging nodes,
panning, zooming and folding cycles affect only presentation. Instructions wrap below
the canvas, or appear in Graph Help on compact layouts. Use Materials for the full
summary when the graph is limited. Candidate keys without summary rows still show
an icon, name and “No summary amount”, without adding demand. The All (`ALL`) filter
searches these candidates; quantity filters use only the corresponding summary amounts.

The screen shows exact material columns and cyclic quantities with filters/search.
Missing-material bookmarking optionally uses EMI favorites (preferred) or JEI
bookmarks; the screen itself requires neither mod. Hold Shift when Start is disabled
to submit a waiting-input order.
An available CPU with enough crafting storage is still required, and each missing
material amount must fit in a signed `long` (at most `Long.MAX_VALUE`). Forced
submission creates no ingredients and does not bypass server submission checks.

Automatic AELIS defaults off. Explicit planner API calls remain available under
their own contracts. BIG_INTEGER input selects explicit exact requests; the
automatic arithmetic preference does not block that service. An exact plan alone
does not give an arbitrary CPU exact execution capability.
Long fields are compatibility projections, not proof of exact physical supply.

Manual confirmation reservations follow the automatic planner switch. Finite
stock reserved by other open confirmations is excluded from new plans and
extraction. Current explicitly infinite sources are excluded from finite locks;
marking is determined by the current cell and access, not by a Long.MAX_VALUE count.

## Native arithmetic and remaining checks

Applied no longer rejects native AE2 plans for 64-bit arithmetic overflow. Exact
task accounting and saturated progress projections remain. Thread/side checks,
real material reservations and dispatch ownership/capacity contracts still apply.

## Migration and logs

Old split/pre-AELIS options migrate to the current functional sections with backups.
Old numeric order settings migrate to the three-state mode. Customized settings
remain preserved when saving nested configuration through Configured.

The diagnostics compatibility setting defaults off. Applied Enhancements emits
no direct console output or INFO, DEBUG or TRACE logs, including planning traces
and startup configuration dumps. Necessary WARN/ERROR messages are limited
to one message per category per severity per minute, with separate rolling budgets of eight
warnings and four errors per minute across all machines. Category history is
bounded at 128 entries per severity; excess categories share an overflow window.
Warnings do not consume the error budget. Logging does not alter rejection,
retry, cancellation or material ownership.

## 中文

上表覆盖当前全部 18 项配置、9 个配置节。配置名称、说明和枚举选项均支持中英文，
包括 Configured Forge 2.2.3；TOML 双语注释包含范围和可选值，存储的枚举标识保持不变。
订单模式为三选一：
关闭接管、long 上限、大整数。自动 AELIS 与大整数规划分别控制自动介入和精确
算术偏好；显式精确请求不受偏好开关阻止，不能把 long 投影当作完整订单或无限来源。

AELIS 自定义确认界面默认启用。客户端通用配置已有的 `[crafting.aelis]` 节中，设置
`enable_plan_screen = false` 即可关闭；界面翻译键为 `enable_aelis_plan_screen`，不是
另一项 TOML 配置。配置重新加载后，已经打开的新界面也会切回 AE2 原版确认界面。
此项只控制当前客户端的界面，不影响服务端规划和合成执行。选择数量后直接在新界面
显示计算和重新规划状态，最终结果不是 AELIS 时返回原版。详细阶段遵循服务端进度开关，
关闭该开关时仍有等待动画。

材料列表和合成树使用同一计划。依赖图按需加载，固定上限为 512 个材料节点、2048 条边，
没有额外配置项；拖动、缩放、平移和折叠循环不改变执行。提示自动换行或在紧凑布局下
显示于“图示说明”中，图受限时可切回完整材料汇总。没有汇总行的候选 key 仍显示图标、
名称和“无汇总数量”，不会增加需求；“全部”（`ALL`）搜索覆盖这些候选，数量筛选只依对应汇总数量。

新界面显示精确材料列与循环数量，支持筛选搜索；缺料收藏优先使用 EMI 收藏，
EMI 不可用时使用 JEI 书签；界面本身不依赖这两个模组。开始按钮不可用时按住 Shift
可提交等待补料的订单，仍需
可用 CPU 和足够的合成存储容量，且每种缺料数量不得超过有符号 `long` 上限
`Long.MAX_VALUE`。强制提交不会生成材料或绕过服务端检查。

无限元件通过标签、运行标记或明确兼容来源识别；普通大容量元件不会因数字很大
被视为无限。修改已有节后重启，旧配置迁移会保存备份。诊断选项仅为兼容旧版
OmniSequence 保留。本版无直接控制台输出或 INFO/DEBUG/TRACE 日志，仅保留必要 WARN/ERROR：
每个严重级别的同类别每分钟最多 1 条，全局每分钟最多 8 条 WARN、4 条 ERROR。
