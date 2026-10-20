# Configuration / 配置参考

Minecraft 1.21.1 / NeoForge.

Applied Enhancements **1.1.3** generates `config/appliedenhancements-common.toml`
in the active instance. Edit existing sections and restart both sides.
[API index](docs/README.md)

| Path | Default | Behavior / range |
| --- | --- | --- |
| `crafting.max_crafting_order_amount` | DISABLED | DISABLED native; LONG_MAX long input; BIG_INTEGER exact input without the former 256-digit order limit |
| `crafting.enable_progress_display` | false | Calculation progress and planner-path display |
| `crafting.enable_enhanced_material_calculation` | false | Enhanced preview stock/crafting/missing statistics |
| `crafting.aelis.enable_automatic_planner` | false | Automatic AELIS interception and manual-plan stock reservations |
| `crafting.aelis.enable_plan_screen` | true | Local client AELIS plan-review screen; false keeps AE2's original confirmation screen without changing planning or execution |
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

## Planning and reservations

The new AELIS plan screen defaults on. To disable it, set
`enable_plan_screen = false` in the existing `[crafting.aelis]` section on the
client. Once the changed configuration is loaded, an open AELIS plan screen also
returns to the original AE2 view. This is a local presentation preference, not a
server-controlled planner or crafting switch.

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
no planning traces or startup configuration dumps. Repeated faults are limited
to one message per category per minute, with separate rolling budgets of eight
warnings and four errors per minute across all machines. Category history is
bounded at 128 entries per severity; excess categories share an overflow window.
Warnings do not consume the error budget. Logging does not alter rejection,
retry, cancellation or material ownership.

## 中文

上表覆盖当前全部 18 项配置，注释与界面翻译支持中英文。订单模式为三选一：
关闭接管、long 上限、大整数。自动 AELIS 与大整数规划分别控制自动介入和精确
算术偏好；显式精确请求不受偏好开关阻止，不能把 long 投影当作完整订单或无限来源。

新界面默认启用。客户端配置文件已有的 `[crafting.aelis]` 节中，设置
`enable_plan_screen = false` 即可关闭；配置重新加载后，已经打开的新界面也会切回
AE2 原版确认界面。此项只控制当前客户端的界面，不影响服务端规划和合成执行。

无限元件通过标签、运行标记或明确兼容来源识别；普通大容量元件不会因数字很大
被视为无限。修改已有节后重启，旧配置迁移会保存备份。诊断选项仅供兼容联动读取，
本模组不输出规划追踪或启动配置详情。同类故障每分钟最多记录一次；所有机器共用
每分钟最多 8 条警告、4 条错误的独立滚动限额，警告不占用错误额度。
