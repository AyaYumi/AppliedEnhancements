# Applied Enhancements

[English README](README.md)

Applied Enhancements 是一个面向 Applied Energistics 2（AE2）的 NeoForge 功能增强模组。

项目不注册新的方块或物品，主要通过 Mixin 和网络同步扩展 AE2 的自动合成流程：支持 `long` 范围的合成数量、显示合成计算进度、修正超大数量下的材料统计，并提供可选的 MAX_FAST 合成规划器。

> 当前版本：`1.0.1`
>
> 目标平台：Minecraft `1.21.1` / NeoForge / Java `21`

## 功能概览

| 功能 | 当前行为 |
|---|---|
| Long 范围合成 | 合成订单、网络载荷和规划过程使用 `long` 数量；默认单次上限为 1 万亿，可配置到 `Long.MAX_VALUE` |
| 精确数量校验 | 客户端与服务端共同校验输入，拒绝溢出、非法小数、负数以及会导致 AE2 原生规划器计数溢出的请求 |
| 计算进度显示 | 合成确认界面显示等待、准备、编译、执行、原生计算、计划整理、完成或失败等阶段 |
| 规划路径标识 | 计算结果可区分 `MAX_FAST`、`AE2 回退`、`AE2` 和通用的`外部规划器`路径 |
| 重复产物样板筛选 | 在指定样板管理终端提供 AE2 风格按钮，按主产物筛选重复样板，并支持现有搜索框二次搜索 |
| 快速移动样板 | 在样板管理终端中框选、剪切并事务粘贴样板，机器标题提供整组一键剪切/粘贴 |
| 物品右键菜单 | 空手右击 ME 网络物品可取出、合成和复制 ID；右击 JEI 物品可查看配方、复制名称/ID，并在作弊模式下获取物品 |
| MAX_FAST 规划器 | 默认不自动介入；第三方 Mod 可通过公共 API 主动调用，或由管理员通过配置显式启用 |
| 手动计划库存锁 | 仅在自动 MAX_FAST 规划器开启时，预留合成确认界面计划使用的 ME 库存，避免提交前被其他任务抢占 |
| 样板缓存 | 缓存 AE2 样板输入有效性和容器物品结果，使用有界实例缓存控制内存占用 |
| 材料汇总修正 | 使用饱和算术处理超大合成计划，避免存储、合成和缺失数量在预览中溢出 |
| 无限容量显示 | 仅对 AE2 创造存储元件、ExtendedAE 无限元件或显式标记的磁盘使用 `9.2E`，不再探测任意存储实现 |
| AE2WTLib 兼容 | 通过可选 Mixin 修正无线合成终端对负数可用量的处理；未安装 AE2WTLib 时不会加载目标类 |
| Provider 批次接口 | 为第三方合成 Provider 提供一次 CPU 调度周期内成对的批次开始/结束回调 |

## 版本与依赖

| 组件 | 当前要求 | 类型 |
|---|---:|---|
| Minecraft | `1.21.1` | 必需，精确版本 |
| Java | `21` | 开发与运行目标 |
| NeoForge | `21.1.220` 及以上 | 必需；与 OmniSequence: Transfinite 2.0.0 对齐 |
| Applied Energistics 2 | `19.2.17` 及以上 | 必需；与 OmniSequence: Transfinite 2.0.0 对齐 |
| ExtendedAE | `1.21-2.2.32-neoforge` 及以上 | 可选；提供无限元件识别 |
| AE2WTLib | `19.5.1` 及以上 | 可选兼容依赖；按测试整合包版本声明 |
| Just Enough Items | `19.27.0` 及以上 | 可选；提供 JEI 侧栏与书签栏物品右键菜单 |

核心依赖最低版本与 OmniSequence: Transfinite 2.0.0 对齐。当前构建以 AE2 `19.2.17` 编译并验证；由于核心功能使用 AE2 内部类和 Mixin 注入点，升级到新的 AE2 大版本前仍应重新检查客户端、服务端和实际合成流程。

## 安装

目前仓库提供源码构建流程。构建完成后，将以下文件放入客户端和服务端的 `mods` 目录：

```text
build/libs/appliedenhancements-1.0.1.jar
```

同时需要安装匹配版本的 NeoForge 与 AE2。ExtendedAE、AE2WTLib 和 JEI 仅在使用对应兼容功能时安装。

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

规划器只保留一套固定的 `AGGRESSIVE` 执行策略，不再提供 `OFF` / `SAFE` / `AGGRESSIVE` 模式选择。自动接入 AE2 原生规划的功能默认关闭；需要自动接入时，必须在服务端配置中显式启用 `enableAutomaticMaxFastPlanner`。

第三方 Mod 可以通过 `MaxFastCraftingPlanner` 公共接口主动创建会话并调用 MAX_FAST。API 调用不受 `enableAutomaticMaxFastPlanner` 开关影响，因此接入方可以自行决定何时使用 MAX_FAST、何时使用自己的规划器或回退 AE2。

MAX_FAST 受节点数和编译时间预算约束。编译图仅在当前合成计算会话内复用，不会建立跨世界或跨 Grid 的持久全局缓存。

确定性耐久工具可以按剩余耐久容量批量规划，包括 AE2 为模糊输入选中的替代工具、单次配方使用多件同类工具以及多个耐久输入槽。规划器仍会验证每个候选的剩余物变化；随机、上下文相关或混合剩余物行为继续交回兼容路径。

返回后保持不变的催化剂会在事务聚合图中租用一次，普通消耗输入继续沿已编译子图批量规划。多级精华等“每一级都复用同一个催化剂”的递归配方不再对每一级调用大型 AE2 原生子请求；兼容边界中剩余的普通子请求也会先做库存预检并受原生工作量上限保护。

所有未经证明的局部 AE2 原生边界最多直接处理 `8192` 个逻辑物品。超过上限时 MAX_FAST 会撤销本次尝试并交给后续规划器或 AE2，避免特殊容器、耐久和候选分支在本模组内部执行数十万次线性请求。无效或非正的样板产量会直接回退，不再猜测为单产出。

### 手动合成计划库存锁

库存锁严格跟随 `enableAutomaticMaxFastPlanner`，没有独立开关。自动规划器关闭时不会创建预留，现有确认界面持有的预留也会停止限制提取并在下次菜单更新时释放；第三方通过 `MaxFastCraftingPlanner` API 主动调用规划器不会隐式开启库存锁。

规划完成并仍停留在 AE2 合成确认界面时，服务端会全量预留 `ICraftingPlan.usedItems()` 中的物品和流体。任意材料无法完整预留时不保留部分结果，而是根据扣除了其他确认菜单预留量的库存重新计算。实际提取和模拟提取都会保护其他计划的份额；订单提交期间只允许当前计划使用自己持有的份额。

成功提交、返回数量界面、重新规划、关闭菜单或提交异常都会释放预留。普通提交失败会保留预留，允许玩家在同一确认界面选择其他 CPU 后重试。预留只存在于内存中，不跨服务器重启保存，也不会锁定 CPU、能源、样板或供应器处理能力。

## 重复产物样板筛选

以下终端的搜索框旁会显示一个 AE2 风格的重复筛选按钮：

- AE2 样板管理终端；
- AE2WTLib 无线样板管理终端；
- ExtendedAE 扩展样板管理终端；
- ExtendedAE 无线扩展样板管理终端。

启用后，只保留主产物在当前终端中出现至少两次的样板，并按主产物自动聚类排序；相同产物的样板会连续排列并显示浅蓝色槽位背景。重复判断使用完整的 `AEKey`，忽略产出数量；同一产物即使每次产出数量不同，也会被视为重复。搜索框会继续在重复结果内筛选，关闭按钮后恢复终端原有列表。排序只改变客户端显示，点击操作仍映射到原供应器和原槽位。

## 快速移动样板

上述四类样板管理终端的搜索栏旁提供独立的“移动”按钮：

- 开启后按住左键拖动可框选当前可见样板，每次框选都会替换上一次选择；
- 左键单击样板可切换其选中状态；
- 对已选择样板右键会打开 AE 风格二级菜单并显示“剪切”；
- 对空样板槽右键会打开二级菜单并显示“粘贴”，且不会触发原终端的取出操作；
- 每个机器组标题前提供“剪”和“贴”按钮，用于整组一键剪切和粘贴；
- 关闭快速移动模式或退出当前终端会清除选择与剪切缓存。

粘贴由服务端重新验证来源、目标与容量。只有全部样板都能放入时才提交；任意目标无效、空间不足或执行异常都会取消操作并恢复库存。

## 网络物品右键菜单

在 AE2 存储终端及其兼容子类中，空手右击一个网络物品槽会打开操作菜单：

- “取出 1 个”沿用 AE2 的光标提取语义；
- “取出一组”把一组物品移动到玩家背包；
- “自定义数量”接受精确整数和 AE2 数学表达式，并把玩家背包能容纳的部分取出；
- 可合成条目提供“发起合成”；
- “复制物品 ID”写入系统剪贴板。
- “复制名称”复制当前本地化显示名称；
- “搜索同模组物品”把 `@modid` 写入当前 ME 终端搜索框；

手持物品或容器时不会接管右键，AE2 原有的存入与容器填充行为保持不变。自定义数量请求在服务端重新校验当前菜单、同步序号、网络连接、能源、实时库存和玩家背包容量；客户端显示数量不能强制服务器多取物品。

安装 JEI `19.27.0` 及以上时，右击 JEI 物品侧栏或书签栏条目会显示：

- 查看合成表；
- 查看用途；
- 复制本地化名称；
- 复制注册 ID；
- 搜索同模组物品；
- 当前正在使用 AE2 存储终端时，可搜索当前物品，并按实时网络条目取出 1 个/一组或发起合成；
- JEI 作弊模式开启且 JEI 允许该物品作弊获取时，显示“获取 1 个”和“获取一组”。

作弊获取完全复用 JEI 自己的作弊模式、服务端权限同步和物品给予方式；本模组不会绕过 JEI 或服务器权限。未安装 JEI 时，兼容类不会加载，AE2 网络物品菜单继续独立工作。

菜单触发键可以在“选项 → 控制 → 按键绑定 → Applied Enhancements → 打开物品右键菜单”中修改。默认是鼠标右键，也可以改为其他鼠标键或键盘键；该绑定统一控制 JEI、书签栏、AE2 网络物品以及快速移动模式中的样板剪切/粘贴菜单，只在 GUI 中生效，不影响世界交互。

### 第三方规划器边界

项目不根据特定 Mod ID、类名或约定优先级协调第三方规划器。第三方实现返回非 AE2 原生的 `ICraftingPlan` 时，结果界面会统一标记为`外部规划器`；该标记只用于展示，不代表已经解决多个规划器同时修改 AE2 流程时的执行顺序冲突。

默认配置下 MAX_FAST 不会自动修改规划结果。未接入公共 API 的整合包只有在明确需要本模组接管 AE2 原生规划时，才应将 `enableAutomaticMaxFastPlanner` 设为 `true`。旧版的 `enableMaxFastPlanner` 配置键已失效，不会在升级后意外开启自动规划。

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
| `maxfast.enableAutomaticMaxFastPlanner` | `false` | 布尔值 | 允许本模组自动接入 AE2 原生规划，并启用手动计划库存锁；不影响第三方 API 调用 |
| `maxfast.maxFastMaxNodes` | `100000` | `1000` ～ `1000000` | 单次分析允许的最大节点数 |
| `maxfast.maxFastCompileBudgetMs` | `2000` | `100` ～ `30000` | 单次配方树分析的时间预算，单位为毫秒 |
| `debug.maxFastDiagnostics` | `false` | 布尔值 | 输出详细的编译、执行与回退诊断日志 |

`maxFastDiagnostics` 会产生大量日志，只应在定位回退原因或兼容问题时临时启用。

## 无限存储磁盘标记

无限显示采用显式标记，不再通过模拟提取猜测某个磁盘是否无限。内置识别以下实现：

| 来源 | 内置支持 |
|---|---|
| AE2 | `ae2:creative_storage_cell` 创造存储元件 |
| ExtendedAE | `extendedae:infinity_water_cell`、`extendedae:infinity_cobblestone_cell`，以及使用其 `InfinityCellInventory` 的自定义无限元件 |
| 其他 Mod / 数据包 | 物品标签 `#appliedenhancements:infinite_storage_cells` |
| Java 接入 | 运行时 `StorageCell` 实现 `InfiniteStorageCellMarker` |

被识别的磁盘会在物品内容提示和 ME 网络数量中使用 `Long.MAX_VALUE` 哨兵，并紧凑显示为 `9.2E`。标记只声明磁盘本身已经提供无限内容，不会把普通有限磁盘变成真正的无限物品来源。

KubeJS 可以直接向公共物品标签添加磁盘：

```javascript
ServerEvents.tags('item', event => {
  event.add('appliedenhancements:infinite_storage_cells', [
    'examplemod:infinite_item_cell',
    'examplemod:infinite_fluid_cell'
  ])
})
```

标签在数据包重载后生效。对于不能稳定关联回物品的特殊存储实现，模组作者应同时让运行时 `StorageCell` 实现 `InfiniteStorageCellMarker`。

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
build/libs/appliedenhancements-1.0.1.jar
```

## 验证范围

自动化测试目前覆盖以下重点：

- `long` 数量解析、饱和加法/乘法和原生规划器安全边界；
- 服务端配置同步、计算进度生命周期和路径网络 ID；
- MAX_FAST 执行策略、递归保护、候选回退、数量反馈、稀疏容量求解和深度边界；
- 合成 CPU 执行数量、模拟库存差量、外部计划材料汇总和终端任务生命周期；
- 无限存储白名单、物品标签、运行时标记和网络安全汇总；
- 网络物品菜单注册顺序与精确数量提取载荷边界；
- Provider 批次回调的正常与异常退出配对；
- 手动计划库存预留的全有或全无、并发防超卖、提交所有权与幂等释放；
- Mixin 所属包和目标源码的结构性保护。

`runGameTestServer` 可检查服务端启动和实际加载到的 Mixin，但仓库当前没有场景化 GameTest。客户端界面仍应通过 `runClient` 手动验证普通数量、超大数量、非法数量、进度显示和配置关闭后的原生流程。

## 开发者接口

完整的依赖配置、生命周期、线程/侧别要求及接入示例见 [API 接入文档](docs/API_INTEGRATION_ZH.md)。稳定兼容范围仅包括 `com.appliedenhancements.api` 与 `com.appliedenhancements.api.client`；Mixin、运行时实现和 `com.github.appliedenhancements` 下的内部桥接不属于公共 API。

| 接口 | 用途 |
|---|---|
| `MolecularBalancedBatchProvider` | 在一次 AE2 合成 CPU 调度内接收成对的批次开始与结束回调；异常退出也会关闭批次 |
| `MaxFastCraftingPlanner` | 为其他 Mod 提供 MAX_FAST 会话创建、进度回调、执行结果和失败状态自动回滚接口 |
| `InfiniteStorageCellMarker` | 由第三方运行时 `StorageCell` 实现，声明其内容应使用无限哨兵显示 |
| `InfiniteStorageCells.ITEM_TAG` | 公共物品标签 `#appliedenhancements:infinite_storage_cells`，供数据包和 KubeJS 标记磁盘 |
| `PatternDuplicateApi` | 解析样板产物、按忽略数量的产物键查找重复样板，并注册第三方样板解析器 |
| `PatternOutputResolver` | 让第三方编码样板向重复筛选功能提供有序产物键 |
| `PatternBatchMoveApi` | 发起快速移动请求，并为自定义服务端菜单注册原子移动处理器 |
| `PatternTerminalIntegrationApi` | 注册兼容 AE2 或 ExtendedAE 行布局的第三方样板终端界面 |
| `PatternQuickMoveSession` | 客户端每屏幕的框选、剪切缓存、覆盖层和粘贴请求会话 |
| `NetworkItemContextMenuApi` | 在 ME 终端网络物品右键菜单中注册第三方客户端操作项 |
| `PatternSlotRef` | 使用服务端容器 ID 与容器内槽号稳定标识当前终端中的样板槽 |

公共接口直接引用 AE2 类型，因此开发者依赖至少需要 AE2 `19.2.17`，并应在相同的 AE2 主版本内完成兼容验证。

`MaxFastCraftingPlanner.createConfigured(...)` 使用服务端配置的节点数与编译预算，但不会检查 `enableAutomaticMaxFastPlanner`。每个 AE2 合成计算应创建一个会话，并在该计算的实际尝试和模拟尝试之间复用；当结果的 `shouldFallback()` 为 `true` 时，API 已恢复缺失物品计数与候选状态，调用方可以安全地继续自己的规划器或 AE2 原生流程。

```java
var planner = MaxFastCraftingPlanner.createConfigured(
        MaxFastCraftingPlanner.NO_PAUSE,
        MaxFastCraftingPlanner.ProgressListener.NONE);

var result = planner.tryExecute(
        root, inventory, requestedAmount, simulation, missingItems);
if (result.branchFailure() != null) {
    throw result.branchFailure();
}
if (result.shouldFallback()) {
    // 调用方继续自己的规划器，或进入 AE2 原生 request 流程。
}
```

如果 Applied Enhancements 是可选依赖，接入 Mod 应把上述调用放在仅当本模组已加载时才会加载的兼容类中。

### 重复样板与快速移动 API

第三方编码样板如果不能被 AE2 的 `PatternDetailsHelper` 解码，可以注册产物解析器。返回列表的第一个 `AEKey` 是重复分组使用的主产物；数量不属于分组键。返回空列表表示当前解析器不处理该物品，API 会继续尝试低优先级解析器，最后回退 AE2 原生解码。

```java
PatternDuplicateApi.registerOutputResolver(
        ResourceLocation.fromNamespaceAndPath("examplemod", "custom_patterns"),
        100,
        (stack, level) -> {
            if (!isExamplePattern(stack)) {
                return List.of();
            }
            return List.of(resolveExampleOutput(stack));
        });
```

兼容现有行模型的终端可以在客户端初始化阶段注册其精确界面类名。本模组随后会自动添加“重复”“移动”“剪”“贴”、框选和右键菜单。注册为 `AE2_PATTERN_ACCESS` 的界面必须继承 AE2 样板管理终端并保持其行布局；注册为 `EXTENDEDAE_PATTERN_ACCESS` 的界面必须继承 ExtendedAE 扩展样板终端并保持对应布局。

```java
PatternTerminalIntegrationApi.register(
        ResourceLocation.fromNamespaceAndPath("examplemod", "pattern_terminal"),
        PatternTerminalIntegrationApi.Family.AE2_PATTERN_ACCESS,
        "examplemod.client.gui.ExamplePatternAccessScreen");
```

继承 `PatternAccessTermMenu` 的服务端菜单会自动获得内置的原子移动实现。完全自定义菜单可以直接实现 `PatternBatchMoveApi.MenuExtension`，或者通过 `registerMenuHandler(...)` 注册处理器。处理器必须在服务端重新验证玩家权限、来源样板、目标槽和容量，并保证失败时不留下部分移动结果；不得相信客户端提供的槽位内容。

完全自定义的客户端行模型可以自行调用 `PatternDuplicateApi` 构建分组，并为每个打开的界面创建一个 `PatternQuickMoveSession`。关闭界面时必须调用 `clear()`，确保剪切缓存不会跨终端保留。KubeJS 不提供这些界面与移动接口。

## 已知限制

| 限制 | 影响 |
|---|---|
| AE2 最低版本为 `19.2.17` | 元数据允许更高版本，但新的 AE2 大版本仍需重新验证内部 Mixin 注入点 |
| MAX_FAST 只缓存当前计算会话 | 不提供跨 Grid、跨世界或持久化的配方图缓存 |
| 没有场景化 GameTest | 自动化测试不能替代真实整合包中的客户端与服务端验证 |
| MAX_FAST 采用单一激进策略 | 自动接入默认关闭；启用前应在实际整合包中验证配方兼容性 |
| 超大订单仍受资源限制 | 合法的 `long` 数量不代表一定能在可接受时间和内存内完成 |

## 许可证

本项目采用 [MIT License](LICENSE)。
