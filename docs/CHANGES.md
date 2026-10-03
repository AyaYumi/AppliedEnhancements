# Changes / 本版变更

## 1.1.1-forge

- Native smart-doubling patterns bypass local plan rewrites. EAEP enabled state is checked per pattern; Useless/EAEP wrappers keep their native interfaces and exact multiplier, without adding `AelisScaledPattern`.
- Preserve native provider splits and remainders while reconciling mixed local/external tasks. BigInteger task ledgers retain all work; unknown or inconsistent rewrites remain rejected with the pattern class in the error.
- Added cached optional ABI access, both Useless submission overloads and regressions for enabled/disabled switches, mixed tasks, native split conservation and Forge permissive remapping. NeoForge additionally checks the installed EAEP 1.5.5 class.

中文

- 已开启原生智能倍增的样板跳过本地改写，逐样板读取 EAEP 开关，EAEP/无用之物包装保留原生接口和倍率，不再附加 Applied 倍率接口。
- 混合任务保留原生供应器分配、尾数及完整大整数数量；未知或数量不守恒的改写仍拒绝，并在异常里附带样板类名。
- 可选接口按类缓存，覆盖两种提交入口，新增开关、混合任务、倍率拆分守恒及 Forge 方法匹配回归。

## 1.1.0-forge

- Ported the complete 1.1.0 feature set to Java 17 / Forge 1.20.1, including
  three crafting order modes, exact BigInteger planning and terminal integration.
- Support upstream AE2 15.4.10 and UELM 15.5.4 with the same runtime JAR.
  Confirmation hooks cover both native submit entry points without dropping finite
  stock reservations. Optional creative-cell IDs no longer break tag loading.
- Merge exact CPU-screen quantities before native sorting, supporting UELM's
  extended status entries while preserving its external-pending metadata.
- Recheck the requested item type on every AE2 15 external inventory retry,
  preventing extraction of a replacement item when a slot changes type.
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
