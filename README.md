# Applied Enhancements

Applied Enhancements 是一个面向 Applied Energistics 2（AE2）的 NeoForge 功能增强模组。

项目不注册新的方块或物品，主要通过 Mixin 和网络同步扩展 AE2 的自动合成流程：支持 `long` 范围的合成数量、显示合成计算进度、修正超大数量下的材料统计，并提供可选的 MAX_FAST 合成规划器。

> 当前版本：`1.0.0`
>
> 目标平台：Minecraft `1.21.1` / NeoForge / Java `21`

## 功能概览

| 功能 | 当前行为 |
|---|---|
| Long 范围合成 | 合成订单、网络载荷和规划过程使用 `long` 数量；默认单次上限为 1 万亿，可配置到 `Long.MAX_VALUE` |
| 精确数量校验 | 客户端与服务端共同校验输入，拒绝溢出、非法小数、负数以及会导致 AE2 原生规划器计数溢出的请求 |
| 计算进度显示 | 合成确认界面显示等待、准备、编译、执行、原生计算、计划整理、完成或失败等阶段 |
| 规划路径标识 | 计算结果可区分 `MAX_FAST`、`AE2 回退`、`AE2` 和 `EcoAE` 路径 |
| MAX_FAST 规划器 | 尝试聚合并批量执行兼容的配方图；不兼容或超出预算时按模式回退到 AE2 原生规划 |
| 样板缓存 | 缓存 AE2 样板输入有效性和容器物品结果，使用有界实例缓存控制内存占用 |
| 材料汇总修正 | 使用饱和算术处理超大合成计划，避免存储、合成和缺失数量在预览中溢出 |
| 无限容量显示 | 对无限存储容量和并行度使用 `9.2E` 紧凑显示，并对网络存储汇总使用安全的饱和加法 |
| AE2WTLib 兼容 | 通过可选 Mixin 修正无线合成终端对负数可用量的处理；未安装 AE2WTLib 时不会加载目标类 |
| Provider 批次接口 | 为第三方合成 Provider 提供一次 CPU 调度周期内成对的批次开始/结束回调 |

## 版本与依赖

| 组件 | 当前要求 | 类型 |
|---|---:|---|
| Minecraft | `1.21.1` | 必需，精确版本 |
| Java | `21` | 开发与运行目标 |
| NeoForge | 开发环境 `21.1.220`；运行时允许 `21.1.0` 及以上 | 必需 |
| Applied Energistics 2 | `19.2.17` | 必需，精确版本 |
| ExtendedAE | `1.21-2.2.32-neoforge` 及以上 | 可选元数据依赖 |
| AE2WTLib | `1.21.1-19.2.16-neoforge` 及以上 | 可选兼容依赖 |

核心功能依赖 AE2 `19.2.17` 的内部类和 Mixin 注入点。升级 AE2 后即使能够编译，也必须重新检查所有 Mixin 并完成客户端、服务端和实际合成流程验证。

## 安装

目前仓库提供源码构建流程。构建完成后，将以下文件放入客户端和服务端的 `mods` 目录：

```text
build/libs/appliedenhancements-1.0.0.jar
```

同时需要安装匹配版本的 NeoForge 与 AE2。ExtendedAE 和 AE2WTLib 仅在使用对应兼容功能时安装。

本模组包含服务端配置同步和客户端界面 Mixin，联机环境建议客户端与服务端同时安装相同版本。

## Long 范围合成

数量输入框最多接受 20 个字符，并采用精确整数解析。服务器会再次校验功能开关与订单上限，因此客户端配置无法绕过服务器限制。

默认最大订单量：

```text
1,000,000,000,000
```

配置允许的理论最大值：

```text
9,223,372,036,854,775,807
```

以下类型的输入会被拒绝，不会截断或回绕成其他数值：

```text
9223372036854775808
1.5
-1
```

即使输入处于 `long` 范围内，如果某个配方分支的乘法、输出聚合或 AE2 原生逐件尝试会越过安全边界，请求仍可能被拒绝。超大订单能否实际完成还取决于配方图规模、网络库存、内存和执行时间。

## MAX_FAST 规划器

MAX_FAST 会在单次合成计算会话中分析配方树，并尝试把可证明安全的节点聚合执行。存在容器物品、复杂候选、可复用输入或其他兼容边界时，规划器会保留局部原生语义，或把整次尝试交回 AE2。

| 模式 | 行为 |
|---|---|
| `OFF` | 关闭 MAX_FAST，完全使用 AE2 原生规划 |
| `SAFE` | 默认模式；执行保守兼容性检查，不适用时回退 AE2 |
| `AGGRESSIVE` | 放宽部分兼容性限制，可能直接暴露规划错误，仅建议排障或受控测试时使用 |

MAX_FAST 受节点数和编译时间预算约束。编译图仅在当前合成计算会话内复用，不会建立跨世界或跨 Grid 的持久全局缓存。

### EcoAE 退让规则

启用 `maxFastAutoYield` 且检测到 EcoAE 时，MAX_FAST 会比较规划器优先级。数值越小表示优先级越高；EcoAE 的约定优先级为 `50`。

默认 `maxFastPlannerPriority = 500`，因此默认配置下会向 EcoAE 退让。如需让 MAX_FAST 优先，需要关闭自动退让，或在充分验证兼容性后调整优先级。

## 配置

首次启动后会生成两个 COMMON 配置文件。

### `config/appliedenhancements-common.toml`

| 配置键 | 默认值 | 有效范围 | 说明 |
|---|---:|---:|---|
| `crafting.max_crafting_order_amount` | `1000000000000` | `1` ～ `Long.MAX_VALUE` | 单次 AE2 自动合成订单的最大数量 |
| `caching.enable_pattern_caching` | `true` | 布尔值 | 启用样板输入验证与容器物品缓存 |
| `caching.pattern_cache_size` | `32` | `8` ～ `256` | 每个样板的多键缓存最大条目数 |
| `crafting_plan.enable_enhanced_material_calculation` | `true` | 布尔值 | 启用增强的存储、合成和缺失材料统计 |

### `config/appliedenhancements-maxfast.toml`

| 配置键 | 默认值 | 有效范围 | 说明 |
|---|---:|---:|---|
| `features.enableLongRangeCrafting` | `true` | 布尔值 | 启用超过 `Integer.MAX_VALUE` 的合成订单 |
| `features.enableProgressDisplay` | `true` | 布尔值 | 启用合成计算进度和路径显示 |
| `maxfast.enableMaxFastPlanner` | `true` | 布尔值 | 启用 MAX_FAST 规划器 |
| `maxfast.maxFastMode` | `SAFE` | `OFF` / `SAFE` / `AGGRESSIVE` | 选择规划器模式 |
| `maxfast.maxFastMaxNodes` | `100000` | `1000` ～ `1000000` | 单次分析允许的最大节点数 |
| `maxfast.maxFastCompileBudgetMs` | `2000` | `100` ～ `30000` | 单次配方树分析的时间预算，单位为毫秒 |
| `maxfast.maxFastPlannerPriority` | `500` | `0` ～ `10000` | 与其他规划器比较的优先级，数值越小越优先 |
| `maxfast.maxFastAutoYield` | `true` | 布尔值 | EcoAE 优先级更高时自动退让 |
| `debug.maxFastDiagnostics` | `false` | 布尔值 | 输出详细的编译、执行与回退诊断日志 |

`maxFastDiagnostics` 会产生大量日志，只应在定位回退原因或兼容问题时临时启用。

## 构建与运行

项目使用 Gradle Wrapper `8.14.2`，无需单独安装 Gradle，但需要可用的 JDK 21。

| 任务 | Windows | Linux / macOS |
|---|---|---|
| 运行单元测试 | `.\gradlew.bat test --no-daemon --console=plain` | `./gradlew test --no-daemon --console=plain` |
| 构建 JAR | `.\gradlew.bat build --no-daemon --console=plain` | `./gradlew build --no-daemon --console=plain` |
| 启动开发客户端 | `.\gradlew.bat runClient` | `./gradlew runClient` |
| 启动开发服务端 | `.\gradlew.bat runServer` | `./gradlew runServer` |
| 启动 GameTest 服务端 | `.\gradlew.bat runGameTestServer --no-daemon --console=plain` | `./gradlew runGameTestServer --no-daemon --console=plain` |

构建产物位于：

```text
build/libs/appliedenhancements-1.0.0.jar
```

## 验证范围

自动化测试目前覆盖以下重点：

- `long` 数量解析、饱和加法/乘法和原生规划器安全边界；
- 服务端配置同步、计算进度生命周期和路径网络 ID；
- MAX_FAST 执行策略、递归保护、候选回退、数量反馈、稀疏容量求解和深度边界；
- 合成 CPU 执行数量、模拟库存差量、外部计划材料汇总和终端任务生命周期；
- 无限存储识别与网络存储检测缓存；
- Provider 批次回调的正常与异常退出配对；
- Mixin 所属包和目标源码的结构性保护。

`runGameTestServer` 可检查服务端启动和实际加载到的 Mixin，但仓库当前没有场景化 GameTest。客户端界面仍应通过 `runClient` 手动验证普通数量、超大数量、非法数量、进度显示和配置关闭后的原生流程。

## 开发者接口

| 接口 | 用途 |
|---|---|
| `MolecularBalancedBatchProvider` | 在一次 AE2 合成 CPU 调度内接收成对的批次开始与结束回调；异常退出也会关闭批次 |
| `LongCraftingAmountMenuBridge` | 为数量菜单暴露 `long` 合成请求入口 |
| `LongCraftingConfirmMenuBridge` | 为确认菜单保存并提交 `long` 订单量 |
| `OmniCalculationPathCarrier` | 在计算计划与客户端界面之间传递规划路径 |
| `InfiniteConstants` | 提供无限存储容量与无限并行度的哨兵常量 |

公共接口直接引用 AE2 类型，因此开发者依赖必须与本项目固定的 AE2 版本保持一致。

## 已知限制

| 限制 | 影响 |
|---|---|
| AE2 依赖固定为 `19.2.17` | 其他 AE2 版本不在当前兼容范围内 |
| MAX_FAST 只缓存当前计算会话 | 不提供跨 Grid、跨世界或持久化的配方图缓存 |
| 没有场景化 GameTest | 自动化测试不能替代真实整合包中的客户端与服务端验证 |
| `AGGRESSIVE` 模式可能硬失败 | 不建议用于未经验证的生产存档 |
| 超大订单仍受资源限制 | 合法的 `long` 数量不代表一定能在可接受时间和内存内完成 |

## 许可证状态

项目构建元数据当前将许可证声明为 `MIT`，但仓库尚未包含独立的 `LICENSE` 文件。在正式分发或复用代码前，应由项目所有者补充并确认完整许可证文本。
