# Changes / 本版变更

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
