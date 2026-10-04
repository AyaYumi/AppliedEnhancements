# Changes / 当前版本变更

## 1.1.0-forge

- Share protected batch input extraction, actual dispatch counts, durable material ownership and rollback through `AelisBatchExecutionContext`.
- Query native smart-doubling state through `AelisSmartDoublingApi`. Preserve external wrappers, provider splits and exact remainders; unreadable rewrites restore authoritative original tasks.
- Allow explicit exact requests independently of the automatic arithmetic preference. Support `REPORT_MISSING_ITEMS` and `CRAFT_LESS`, including reduced native attempts, and normalize non-positive amounts to empty requests. Quantity input and transport allow up to 1,048,576 characters.
- Allow native AE2 fallback after optimization fails. Retain actual calculated output, exact metadata, marked infinite-source handling, finite reservations, cache validation and per-tick budgets.
- Preserve item identity checks when external storage changes type during an extraction retry.
- Keep separate request and amount-restoration packet types on Forge; returning from confirmation preserves long and BigInteger quantities. Client and server use protocol `1.1.0-forge-2`.

中文

- 共享批量投料上下文统一保护库存、实际派发次数、持久材料所有权和回滚。
- 原生倍增查询保留外部包装、供应器分配和精确尾数；无法读取的改写恢复原始任务。
- 显式精确请求独立于自动算术偏好，支持缺料报告与尽量合成；非正数量按空请求处理，输入和传输上限为 1,048,576 字符。
- 优化失败可回退原生 AE2，记录实际产量并保留精确元数据、无限来源标记、有限材料预留、缓存验证和 Tick 工作预算。
- 外部库存重试抽取时重新核对物品类型，避免取走已经替换的物品。
