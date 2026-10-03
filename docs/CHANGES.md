# Changes / 本版变更

## 1.1.1 — native arithmetic interception removal / 移除原生算术拦截

- Removed Applied's native AE2 64-bit planner rejection and all five rejection Mixins. Maximum-valued cobblestone/water inventory and returned byproducts no longer trigger `UnsafeNativeCraftingRequestException` from this mod.
- Retained exact native task accumulation, CPU progress projections, marked infinite-source handling, smart-doubling compatibility and TPS budgets. Native AE2 arithmetic now runs without this rejection; paired Omni still saturates its shared KeyCounter.
- Other operation checks remain unchanged and are listed in [the interception inventory](remaining-interceptions.md) for review.

中文

- 删除 Applied 对原生 AE2 64 位规划算术的拒绝逻辑和五处拦截 Mixin，圆石／水库存上限与副产物不再触发本模组的该异常。
- 保留精确任务累计、CPU 进度投影、已标记无限来源处理、原生智能倍增兼容与 TPS 工作预算。取消拒绝不代表原生 long 算术变成任意精度；配套 Omni 的 KeyCounter 饱和处理仍保留。
- 其他检查尚未删除，已整理成带编号的清单供确认。

## 1.1.1

- Native smart-doubling patterns bypass local plan rewrites. EAEP enabled state is checked per pattern; Useless/EAEP wrappers keep their native interfaces and exact multiplier, without adding `AelisScaledPattern`.
- Preserve native provider splits and remainders while reconciling mixed local/external tasks. BigInteger task ledgers retain all work; unknown or inconsistent rewrites remain rejected with the pattern class in the error.
- Restore the per-retry item identity check on AE2 19.2.17 external storage, including when optional indexes are disabled.
- Added cached optional ABI access, both Useless submission overloads and regressions for enabled/disabled switches, mixed tasks, native split conservation and Forge permissive remapping. NeoForge additionally checks the installed EAEP 1.5.5 class.

中文

- 已开启原生智能倍增的样板跳过本地改写，逐样板读取 EAEP 开关，EAEP/无用之物包装保留原生接口和倍率，不再附加 Applied 倍率接口。
- 混合任务保留原生供应器分配、尾数及完整大整数数量；未知或数量不守恒的改写仍拒绝，并在异常里附带样板类名。
- 恢复 AE2 19.2.17 外部存储每次重试抽取前的物品类型检查，关闭可选索引时也生效。
- 可选接口按类缓存，覆盖两种提交入口，新增开关、混合任务、倍率拆分守恒及 Forge 方法匹配回归。

## 1.1.0

- Added AelisBatchExecutionContext: one protected extraction view, actual phase count,
  enclosing/native dispatch sharing, acceptance and rejected-state rollback. An
  explicit-runtime overload lets independent CPUs avoid internal scope classes.
- Preserved exact inventory/plan metadata and finite confirmation reservations;
  explicitly infinite sources remain excluded from finite reservations.
- Updated current mounted-cell classification and cache ownership behavior.
- Bounded planner/runtime/reload traces and faults globally; category capacity no
  longer clears history and repeats earlier messages. Diagnostics default off.
- Rebuilt current Chinese/English READMEs, API index, batch contract, configuration
  and development docs. Public JavaDoc can be regenerated with apiJavadoc.
- Archived extracted inspection metadata, generated logs/worlds and the dated
  BigInteger audit outside the project. Maintained logo artwork and regressions remain.

本版共享事务避免循环批量首份/额外份数重新获得保护预算或重复推进。日志调整保留
故障与材料处理行为，测试覆盖拒收、已接收后异常、同步产物回收、缓存和并发限流。
