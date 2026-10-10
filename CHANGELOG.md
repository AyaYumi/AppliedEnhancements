# Changes / 当前版本变更

## 1.1.1-forge

- Backport all current Minecraft 1.21.1 planning and public API updates to Minecraft 1.20.1 / Forge, retaining Java 17, AE2 15.4.10 and UELM 15.5.4 compatibility.
- Restore attempt state and fall back to native planning for recoverable planner/progress failures, invalid optional metadata, incompatible AE2 Crafting Tree displays and unbalanced scope cleanup.
- Preserve every authoritative original task when external batch reconciliation cannot produce a complete replacement; missing simulation integrations preserve the native plan.
- Move configuration and release notes to the project root, expand bilingual API contracts and exclude editor artifacts from packaged resources.

- Share protected batch input extraction, actual dispatch counts, durable material ownership and rollback through `AelisBatchExecutionContext`.
- Query native smart-doubling state through `AelisSmartDoublingApi`. Preserve external wrappers, provider splits and exact remainders; unreadable rewrites restore authoritative original tasks.
- Allow explicit exact requests independently of the automatic arithmetic preference. Support `REPORT_MISSING_ITEMS` and `CRAFT_LESS`, including reduced native attempts, and normalize non-positive amounts to empty requests. Quantity input and transport allow up to 1,048,576 characters.
- Allow native AE2 fallback after optimization fails. Retain actual calculated output, exact metadata, marked infinite-source handling, finite reservations, cache validation and per-tick budgets.
- Preserve item identity checks when external storage changes type during an extraction retry.
- Keep separate request and amount-restoration packet types on Forge; returning from confirmation preserves long and BigInteger quantities. Client and server use protocol `1.1.0-forge-2`.

中文

- 同步当前 1.21.1 的全部规划修复与公开 API 更新，保留 1.20.1 / Forge、Java 17、原版 AE2 与 UELM 兼容。
- 可恢复的规划及进度回调异常先恢复尝试状态，再回退原生规划；可选元数据和合成树显示失败保留完整原计划。
- 外部批次无法生成完整替换任务时恢复所有原始任务，重复或乱序清理不再中断有效订单。
- 配置与更新记录移至根目录，补全双语接口契约，打包时排除编辑器临时资源。

- 共享批量投料上下文统一保护库存、实际派发次数、持久材料所有权和回滚。
- 原生倍增查询保留外部包装、供应器分配和精确尾数；无法读取的改写恢复原始任务。
- 显式精确请求独立于自动算术偏好，支持缺料报告与尽量合成；非正数量按空请求处理，输入和传输上限为 1,048,576 字符。
- 优化失败可回退原生 AE2，记录实际产量并保留精确元数据、无限来源标记、有限材料预留、缓存验证和 Tick 工作预算。
- 外部库存重试抽取时重新核对物品类型，避免取走已经替换的物品。
