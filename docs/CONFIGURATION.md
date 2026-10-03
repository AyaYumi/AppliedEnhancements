# Configuration / 配置参考

Applied Enhancements **1.1.0-forge** generates `config/appliedenhancements-common.toml`
in the active instance. Edit existing sections and restart both sides.
[API index](README.md) · [Development](DEVELOPMENT.md)

| Path | Default | Behavior / range |
| --- | --- | --- |
| `crafting.max_crafting_order_amount` | DISABLED | DISABLED native; LONG_MAX long input; BIG_INTEGER exact input up to 256 decimal digits |
| `crafting.enable_progress_display` | false | Calculation progress and planner-path display |
| `crafting.enable_enhanced_material_calculation` | false | Enhanced preview stock/crafting/missing statistics |
| `crafting.aelis.enable_automatic_planner` | false | Automatic AELIS interception and manual-plan stock reservations |
| `crafting.aelis.enable_big_integer_planning` | true | Exact intermediate demand and plan metadata; does not independently enable automatic AELIS |
| `crafting.aelis.max_nodes` | 100000 | 1000–1,000,000 nodes per attempt |
| `crafting.aelis.compile_budget_ms` | 2000 | 100–30000 ms per compile attempt |
| `crafting.aelis.enable_diagnostics` | false | Optional process summaries, one-minute global template limit |
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

Automatic AELIS defaults off. Explicit planner API calls remain available under
their own contracts. BIG_INTEGER input also requires exact planning enabled; an
exact plan alone does not give an arbitrary CPU exact execution capability.
Long fields are compatibility projections, not proof of exact physical supply.

Manual confirmation reservations follow the automatic planner switch. Finite
stock reserved by other open confirmations is excluded from new plans and
extraction. Current explicitly infinite sources are excluded from finite locks;
marking is determined by the current cell and access, not by a Long.MAX_VALUE count.

## Migration and logs

Old split/pre-AELIS options migrate to the current functional sections with backups.
Old numeric order settings migrate to the three-state mode. Customized settings
remain preserved when saving nested configuration through Configured.

Diagnostics default off. Repeated runtime/load faults retain a first warning/error
then use a global one-minute category window. Known history is retained at the
128-category limit; excess categories share an overflow window. This bounds logs
without modifying the operation's rejection, retry, cancellation or owned inputs.

## 中文

上表覆盖当前全部 17 项配置，注释与界面翻译支持中英文。订单模式为三选一：
关闭接管、long 上限、大整数。自动 AELIS 与大整数规划分别控制自动介入和精确
算术；需要按使用场景开启，不能把 long 投影当作完整订单或无限来源。

无限元件通过标签、运行标记或明确兼容来源识别；普通大容量元件不会因数字很大
被视为无限。修改已有节后重启，旧配置迁移会保存备份。诊断仅在排查时开启，
重复日志全局限流，首次故障仍可查看。
