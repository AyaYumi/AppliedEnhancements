# Changes / 本版变更

## 1.1.0-forge — crafting amount packet direction fix / 修复下单数量数据包方向

- Split serverbound exact crafting requests from clientbound amount restoration. Clicking Next with enhanced quantity input no longer uses the client packet discriminator and disconnects the player in Forge.
- Restore quantities above the native int range when returning from the confirmation page, including long-valued requests and exact BigInteger requests.
- Keep request validation and tick budgets. The internal protocol is now `1.1.0-forge-2`; update both client and server to this build. Public API signatures and the release version remain unchanged.
- Add regression coverage using Forge's actual packet-class registration and discriminator encoding in both directions.

中文

- 精确下单请求与返回数量页的同步拆为两个数据包类，修复增强数量输入点击“下一步”时被 Forge 判为方向错误并断开连接的问题。
- 返回数量页时同步超 int 的 long 数量与精确 BigInteger 数量，避免返回后数值被截断。
- 保留请求检查和 tick 工作预算；内部协议更新为 `1.1.0-forge-2`，客户端与服务端都需更新到本构建，公共 API 与发行版本号保持不变。
- 新增使用 Forge 实际注册表和包编号编码的双向回归检查。

## 1.1.0-forge — selected planning rejection removal / 移除指定规划拒绝

- Remove A03 ordered-choice/long replay rejection, A19 native fallback rejection and A07 selected-CPU cycle capability rejection. Optimizer budgets remain; failed optimization can continue through native AE.
- Replace A11 smart-doubling rejection with exact remainder repair or restoration of authoritative original tasks. Optional ABI failures keep native ownership without failing local planning.
- Remove A02's 256-digit, strategy and configuration rejection. Explicit exact requests work with the preference disabled; CRAFT_LESS attempts use independent quantities and non-positive requests become empty orders. Input and wire permit longer decimals within the transport envelope.
- Keep other inventory protections, runtime consistency rules, tick budgets, caches and rate-limited diagnostics. Native fallback uses the actual long result and does not fabricate full exact output.

中文

- 删除 A03 候选配方／重放拒绝、A19 原生回退拒绝，以及 A07 指定 CPU 的循环能力拒绝；优化预算保留，优化失败可交回原生 AE。
- A11 数量不一致改为精确尾数修复，未知或不可读改写恢复原始任务；可选接口失败不再让本地规划报错。
- A02 去掉 256 位、计算策略及配置开关拒绝。显式精确请求在偏好关闭时可用，尽量合成的每次尝试独立计数，非正数按空订单处理。输入／网络支持更长数字，传输载荷边界仍保留。
- 其他库存保护、运行状态规则、tick 预算、缓存和日志限流保留；原生回退记录实际 long 结果，不伪造完整精确产量。

## 1.1.0-forge — native arithmetic interception removal / 移除原生算术拦截

- Removed Applied's native AE2 64-bit planner rejection and all five rejection Mixins. Maximum-valued cobblestone/water inventory and returned byproducts no longer trigger `UnsafeNativeCraftingRequestException` from this mod.
- Retained exact native task accumulation, CPU progress projections, marked infinite-source handling, smart-doubling compatibility and TPS budgets. Native AE2 arithmetic now runs without this rejection; paired Omni still saturates its shared KeyCounter.
- Other operation checks remain unchanged and are listed in [the interception inventory](remaining-interceptions.md) for review.

中文

- 删除 Applied 对原生 AE2 64 位规划算术的拒绝逻辑和五处拦截 Mixin，圆石／水库存上限与副产物不再触发本模组的该异常。
- 保留精确任务累计、CPU 进度投影、已标记无限来源处理、原生智能倍增兼容与 TPS 工作预算。取消拒绝不代表原生 long 算术变成任意精度；配套 Omni 的 KeyCounter 饱和处理仍保留。
- 其他检查尚未删除，已整理成带编号的清单供确认。

## 1.1.0-forge — updated build / 更新构建

- Native smart-doubling patterns bypass local plan rewrites. EAEP enabled state is checked per pattern; Useless/EAEP wrappers keep their native interfaces and exact multiplier, without adding `AelisScaledPattern`.
- Preserve native provider splits and remainders while reconciling mixed local/external tasks. BigInteger task ledgers retain all work; unknown or inconsistent rewrites remain rejected with the pattern class in the error.
- Added cached optional ABI access, both Useless submission overloads and regressions for enabled/disabled switches, mixed tasks, native split conservation and Forge permissive remapping. NeoForge additionally checks the installed EAEP 1.5.5 class.
- Retained these fixes in the requested 1.1.0-forge release. Verified real EAEP 1.6.2 enabled/disabled patterns and native multiplier/remainder preservation in Project Infinity 0.1, alongside actual planning, submission and completion. Paired AE2 15.4.10 and UELM 15.5.4 engine checks pass with the same Applied JAR.

中文

- 已开启原生智能倍增的样板跳过本地改写，逐样板读取 EAEP 开关，EAEP/无用之物包装保留原生接口和倍率，不再附加 Applied 倍率接口。
- 混合任务保留原生供应器分配、尾数及完整大整数数量；未知或数量不守恒的改写仍拒绝，并在异常里附带样板类名。
- 可选接口按类缓存，覆盖两种提交入口，新增开关、混合任务、倍率拆分守恒及 Forge 方法匹配回归。
- 按要求使用 1.1.0-forge 版本号并保留全部后续修复。在 Project Infinity 0.1 内验证真实 EAEP 1.6.2 开关、倍率和尾数，以及真实规划、提交和合成完成；同一份 Applied JAR 分别通过 AE2 15.4.10 和 UELM 15.5.4 双模组游戏回归。

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
