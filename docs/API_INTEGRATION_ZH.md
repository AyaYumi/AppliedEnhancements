# Applied Enhancements API 接入文档

[English documentation](API_INTEGRATION.md)

本文面向希望接入 Applied Enhancements `1.0.4` 的 NeoForge 模组作者，涵盖依赖声明、稳定 API、注册生命周期、客户端/服务端边界和失败回退要求。

## 兼容基线

| 组件 | 版本 / 验证基线 | 说明 |
|---|---:|---|
| Minecraft | `1.21.1` | 声明的游戏范围：`[1.21.1]` |
| Java | `21` | 编译与运行目标 |
| NeoForge | `21.1.220` | 构建与运行验证版本；当前声明范围：`[21.1.220,)` |
| Applied Energistics 2 | `19.2.17` | 声明范围：`[19.2.17,)`；公共接口直接引用 AE2 类型 |
| Applied Enhancements | `1.0.4` | 本文档对应版本 |

稳定兼容范围仅包括以下包：

```text
com.appliedenhancements.api
com.appliedenhancements.api.client
```

以下内容属于内部实现，不承诺源码或二进制兼容：

- `com.appliedenhancements.mixin`；
- `com.appliedenhancements.client`；
- `com.appliedenhancements.runtime`；
- `com.appliedenhancements.integration`；
- `com.github.appliedenhancements`；
- 其他未位于公共 API 包中的桥接类、载荷和常量。

## 开发环境依赖

项目暂未发布独立 Maven API 构件。接入方可以把发行 JAR 放入自己项目的 `libs` 目录，并以 `compileOnly` 方式引用：

```groovy
dependencies {
    // 接入方通常已经直接依赖 AE2；其类型出现在本模组的公共签名中。
    compileOnly "org.appliedenergistics:appliedenergistics2:19.2.17"

    // 仅用于编译，不要把 Applied Enhancements 打入自己的 JAR。
    compileOnly files("libs/appliedenhancements-1.0.4.jar")

    // 只有需要在开发运行环境中联调时才添加。
    runtimeOnly files("libs/appliedenhancements-1.0.4.jar")
}
```

如果接入代码会无条件加载公共 API，应在 `neoforge.mods.toml` 中声明硬依赖：

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
type="required"
versionRange="[1.0.4,)"
ordering="AFTER"
side="BOTH"
```

如果只在检测到本模组时加载独立兼容类，可以声明可选依赖：

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
type="optional"
versionRange="[1.0.4,)"
ordering="AFTER"
side="BOTH"
```

可选接入必须把所有 API 引用隔离到只有本模组已加载时才会触发类加载的兼容类中：

```java
if (ModList.get().isLoaded("appliedenhancements")) {
    AppliedEnhancementsCompat.register();
}
```

仅在运行时判断 Mod ID，但让主类字段、方法签名或静态初始化直接引用本 API，仍可能在缺少本模组时产生 `NoClassDefFoundError`。

## API 总览

公开顶层 API 共 17 个，包括以下 16 个现行类型和一个已弃用兼容入口。侧别表示接入方应在哪里调用；共享的 `api` 包中也有操作仅适用于客户端的接口。

| API | 侧别 | 推荐注册/调用阶段 | 用途 |
|---|---|---|---|
| `AelisCraftingPlanner` | 服务端 | 每次 AE2 合成计算 | 主动调用 AELIS 规划器 |
| `MaxFastCraftingPlanner`（已弃用） | 服务端 | 仅供已有 `1.0.3` 接入 | 保留旧规划器接口、回调和结果签名，内部委托 AELIS |
| `AelisCycleExecutionApi` | 服务端 | CPU 提交与执行阶段 | 附加、复制、读取循环元数据并检查 CPU 能力 |
| `AelisCycleExecutionPlan` | 服务端 | 单次合成计划生命周期 | 描述循环步骤、种子下限和受保护键 |
| `AelisCycleSeedPolicy` | 服务端 | 构造循环计划时指定 | 选择最小种子保留或最大吞吐策略 |
| `AelisCycleRuntimeController` | 服务端 | 单个 CPU 任务生命周期 | 约束循环顺序和最终产物暂存 |
| `AelisCycleAwareCpu` | 服务端 | 由自定义 CPU 实现 | 声明支持 AELIS 循环执行 |
| `InfiniteStorageCellMarker` | 双端 | 运行时类型实现 | 标记运行时无限存储实现 |
| `InfiniteStorageCells` | 双端 | 数据包或运行时查询 | 公共无限磁盘物品标签 |
| `PatternDuplicateApi` | 双端可用 | Common Setup 注册 | 解析产物并查找重复或失效样板 |
| `PatternOutputResolver` | 双端可用 | Common Setup 注册 | 解析第三方编码样板产物 |
| `PatternTerminalIntegrationApi` | 客户端 | Client Setup 注册 | 接入兼容的样板终端界面 |
| `PatternBatchMoveApi` | 客户端与服务端 | Common Setup 注册服务端处理器 | 原子批量移动样板 |
| `PatternQuickMoveSession` | 仅客户端 | 每个打开的界面创建 | 管理框选、剪切缓存和覆盖层 |
| `NetworkItemContextMenuApi` | 仅客户端 | Client Setup 注册 | 扩展 ME 网络物品右键菜单 |
| `PatternSlotRef` | 双端 | 当前终端会话内 | 稳定标识机器容器和样板槽 |
| `MolecularBalancedBatchProvider` | 服务端 | Provider 类型实现 | 接收一次 CPU 调度批次的开始/结束回调 |

## 侧别、注册与同步

| 接入能力 | 注册与执行位置 | 同步约定 |
|---|---|---|
| 规划器与循环 CPU | 规划在该逻辑服务端计算所属线程执行；运行中控制器由 CPU 所属服务端线程修改 | 结果、回调、计划与快照都是 Java 对象，不自动发包。自定义 CPU 负责自己的持久化与客户端状态同步 |
| 样板产物解析器 | 在物理客户端与独立服务端的 Common Setup 各注册一次；使用调用方的 `Level` | 注册表只存在于本进程。两端注册应一致，保证客户端筛选与服务端失效样板校验一致 |
| 终端与右键菜单注册 | Client Setup 注册一次；客户端界面类与独立服务端类加载隔离 | 注册与动作留在客户端。服务端改动使用已验证的辅助操作或接入方自己的已验证载荷 |
| 批量移动 | Common Setup 注册服务端处理器；客户端调用 `requestMove`，服务端可调用 `execute` | 公共 API 没有结果回调或 Future；以服务端菜单更新为准，或由接入方增加结果协议 |
| 无限磁盘物品标签 | 服务端数据包加载物品标签，在标签可用后查询 | Minecraft 同步物品标签。Java 标记接口仅表示本地类型能力，不是同步机制 |

使用网络功能时，客户端与服务端应安装同一 Applied Enhancements 发行构建；开发包只有版本字符串相同并不能保证内容一致。`1.0.4` 的内部载荷协议为 `3`。内置网络只同步部分服务端功能配置、计算进度和规划路径显示，不同步第三方注册表或自定义 CPU 状态。载荷类属于内部实现。

注册 API 提供不可修改的快照，但没有注销或替换操作。不要在每次读档、打开界面或连接服务器时重复注册。规划回调在计算上下文中运行，可能位于工作线程；访问界面或世界时，应切换到对应所属线程。

### 从 1.0.3 规划器 API 迁移

`MaxFastCraftingPlanner` 作为已弃用兼容入口继续支持基于 `1.0.3` 编译的接入，包括原有 `PauseCheckpoint`、`ProgressListener`、`Result`、`createConfigured(...)`、`create(...)`、`tryExecute(...)` 签名。它将规划委托给 AELIS，不恢复旧实现，也不把当前界面或配置重新改名为 MAX_FAST。

工厂签名保持 `createConfigured(PauseCheckpoint, ProgressListener)` 和 `create(int, int, PauseCheckpoint, ProgressListener)`。`NO_PAUSE`、`ProgressListener.NONE`、全部五个进度回调及 `Result` 的十一个组件保持 `1.0.3` 的类型。该兼容入口没有 `ICraftingService` 工厂重载，当前弃用注解也未声明计划移除。

新接入使用 `AelisCraftingPlanner`。迁移源码时，规划器类型及其内部回调、结果类型应一起替换，它们是不同的 Java API 类型。需要恢复精确循环候选时使用带 `ICraftingService` 的 AELIS 重载，并遵守下述循环计划提交与 CPU 执行协议。旧类能正常链接不代表独立 CPU 已接入新的阶段执行协议。

## 1. AELIS 规划器

### 基本规则

- 本模组的自动规划接入默认关闭，但其他 Mod 调用公共 API 不受 `crafting.aelis.enable_automatic_planner` 影响。
- 每次 AE2 合成计算创建一个 `AelisCraftingPlanner` 实例。
- 同一计算的真实尝试与模拟尝试可以复用该实例。
- 实例不是线程安全的，不得跨计算根节点或并行线程共享。
- 该 API 使用 AE2 内部合成树类型，因此调用时必须确保 Applied Enhancements 已加载并且对应 Mixin 已生效。

### 创建规划器

使用服务端配置中的节点数和编译时间预算：

```java
AelisCraftingPlanner planner = AelisCraftingPlanner.createConfigured(
        AelisCraftingPlanner.NO_PAUSE,
        AelisCraftingPlanner.ProgressListener.NONE);
```

使用接入方自己的预算和进度回调：

```java
AelisCraftingPlanner planner = AelisCraftingPlanner.create(
        100_000,
        2_000,
        () -> {
            // 可选的协作暂停点；需要取消时抛出 InterruptedException。
        },
        new AelisCraftingPlanner.ProgressListener() {
            @Override
            public void compilationStarted() {
            }

            @Override
            public void nodeDiscovered() {
            }

            @Override
            public void executionStarted(long totalUnits) {
            }

            @Override
            public void executionStep() {
            }
        });
```

### 执行与回退

工厂方法共有四种：`createConfigured(PauseCheckpoint, ProgressListener)`、末尾增加 `ICraftingService` 的重载、`create(int, int, PauseCheckpoint, ProgressListener)`、末尾增加 `ICraftingService` 的重载。两个预算参数必须为正数；暂停点或监听器为 null 时采用空实现，服务重载要求服务非 null。`ProgressListener` 还包含 `compilationStep()` 回调。

```java
AelisCraftingPlanner.Result result = planner.tryExecute(
        root,
        inventory,
        requestedAmount,
        simulation,
        missingItems);

if (result.branchFailure() != null) {
    // 这是 AE2 的终止分支失败，通常应继续向上抛出。
    throw result.branchFailure();
}

if (result.applied()) {
    // AELIS 已把本次请求应用到传入的模拟库存和缺失计数。
    return;
}

if (result.shouldFallback()) {
    // API 已恢复 missingItems 和候选状态，可以安全调用自己的规划器或 AE2 原生路径。
    runNativePlanner();
}
```

`Result` 中的 `fallbackReason`、`error`、节点统计和耗时用于诊断，不应把某个具体回退字符串当成稳定协议。`branchFailure` 非空时不属于普通兼容回退。

`tryExecute` 抛出 `InterruptedException`、运行时异常或错误时，包装层会先恢复本次尝试状态再向上传播。

### API 调用中的循环元数据

自动规划器关闭时，公共 API 仍可生成循环计划、保留种子并提交到支持的 CPU。需要恢复 AE2 递归过滤隐藏的候选时，建议调用带 `ICraftingService` 的重载：

```java
var planner = AelisCraftingPlanner.createConfigured(
        AelisCraftingPlanner.NO_PAUSE,
        AelisCraftingPlanner.ProgressListener.NONE,
        grid.getCraftingService());
```

`tryExecute(...)` 返回 `applied() == true` 后，原生 `CraftingSimulationState.buildCraftingPlan(...)` 已自动附带循环信息。如果接入方自行构造 `ICraftingPlan`，使用公共接口附加元数据：

```java
ICraftingPlan plan = buildCustomPlan();
plan = AelisCycleExecutionApi.attachToPlan(inventory, plan);
```

重新包装计划但未改变循环样板和执行次数时，复制元数据：

```java
ICraftingPlan replacement = buildReplacementPlan(plan);
replacement = AelisCycleExecutionApi.copyMetadata(plan, replacement);
```

这两个接口都会返回应提交的计划；自定义实现可能被包装，必须使用返回值。它们保留循环执行顺序、种子策略、循环材料数量和规划路径，不会修改目标计划的材料统计、订单数量或逻辑总工作量。若循环样板已被数量包装，会还原原始样板并把倍率乘回执行次数；普通样板的包装保持不变。改变循环结构或次数后需要重新规划。以上调用不启用自动规划器，也不隐式启用手动库存锁。

## 2. 循环感知 CPU 执行

AE2 原生 CPU 和 AdvancedAE 量子 CPU 已自动支持。需要接收 AELIS 循环计划的自定义 `ICraftingCPU` 必须实现 `AelisCycleAwareCpu`，并读取公共运行时元数据：

```java
var cyclePlan = AelisCycleExecutionApi.getPlan(plan).orElse(null);
if (cyclePlan != null) {
    var runtime = AelisCycleRuntimeController.withCyclePhase(cyclePlan);
}
```

`AelisCycleExecutionPlan.seedPolicy()` 决定跨订单策略。`PRESERVE_MINIMUM` 会在压缩步骤执行完后继续保护 `minimumSeeds()`；`MAX_THROUGHPUT` 不保留订单结束后的种子下限。启用阶段执行时，两种策略均须等待已跟踪的循环产物结算。

运行时仍存在种子保护期间：

- 只有 `currentStep()` 对应的循环样板可以推进；
- 普通或循环任务派发前调用 `canDispatch(patternDefinition, possibleInputKeys)`；
- 只对当前循环步骤按实际接收次数调用 `patternDispatched(patternDefinition, crafts)`；普通前置或循环计划结束后的工作不能调用；
- 每次 CPU 结算返回产物后调用 `recordReturned(key, settledAmount)`，模拟插入不调用；原版风格的 CPU 应使用待返回数量的实际减少量，因为独立订单的最终产物会转存 ME，而链接接收量可能为零；
- 使用 `requiredRetainedAmount(key)` 或 `maximumConsumableAmount(key, currentlyStored)`，避免普通任务把库存消耗到当前种子下限以下；
- 最终产物交给请求方前调用 `amountToRetain(key, incoming, currentlyStored)`；
- 将 `snapshot()` 与执行计划一起持久化，并通过 `withCyclePhase(plan, state)` 恢复。

通过 `getPlan`、`attachToPlan` 或 `copyMetadata` 获取的完整计划会补充 `phase()`，包含循环的普通前置和各循环样板的真实产物。使用 `withCyclePhase` 显式启用后，`canDispatch` 会在循环阶段阻止无关普通任务，但放行必要前置。`isComplete()` 仍只表示循环步骤已派发完；`isCyclePhaseComplete()` 还要求跟踪的产物全部返回。即使请求数量已经交付，也须等循环阶段完成后再清理订单，避免丢失最后的补种步骤。自行持久化时要保存 `phase()` 和状态中的 `pendingOutputs()`，并通过 `withCyclePhase(plan, state)` 恢复。

旧的 `new AelisCycleRuntimeController(plan)` 和 `(plan, state)` 构造器保留旧的并发执行语义，不启用阶段等待，因此尚未接入 `recordReturned` 的第三方 CPU 不会因升级而卡住。升级 CPU 接入时应同时改用 `withCyclePhase`、结算返回产物并延后完成清理；旧的计划与状态构造器继续可用。

`getPlan(ICraftingPlan)` 返回 `Optional<AelisCycleExecutionPlan>`，在任务图足够完整时补充缺失的阶段信息。应使用返回值，不能假定原始 carrier 已被修改。无法恢复必需样板时，`phase()` 可能仍为 null，保留旧调度行为；控制器工厂不能凭空补齐缺失样板。

| 公共循环类型 | 数据与约束 |
|---|---|
| `AelisCycleExecutionPlan` | 完整构造参数为 `List<Step> steps`、`Map<AEKey, Long> minimumSeeds`、`Set<AEKey> protectedKeys`、`AelisCycleSeedPolicy seedPolicy`、可为空的 `Phase phase`。步骤不能为空，数量必须为正，集合会复制 |
| `Step` | `AEKey patternDefinition`、正数 `long crafts`、`Map<AEKey, Long> inputsPerCraft`、`Set<AEKey> selfReplenishingInputs`。每个输入属于 `protectedKeys`，自补充键属于该步骤输入 |
| `Phase` | `Set<AEKey> prerequisitePatterns`、`Map<AEKey, Map<AEKey, Long>> outputsPerPattern`。产量按每次执行记录，不是整个订单总量。非 null 阶段必须为每个计划内样板定义提供产物映射条目 |
| `AelisCycleRuntimeController.State` | `int stepIndex`、`long remainingCrafts`、`Map<AEKey, Long> pendingOutputs`。两参数构造器生成空待返还表；步骤全部派发完时，索引等于 `steps.size()` 且剩余次数为 `0` |
| `AelisCycleAwareCpu` | 默认 `supportsAelisCycleExecution()` 返回 true。`AelisCycleExecutionApi.supports(ICraftingCPU)` 只检查能力声明，不安装执行钩子 |

计划的三参数构造器保留 `MAX_THROUGHPUT`；四参数构造器接收显式策略，初始无阶段信息。`patternDefinitions()` 返回循环样板定义；`runtime.plan()`、`isCyclePattern(AEKey)`、`currentStep()`、`remainingCrafts()` 用于检查调度状态。`canDispatch` 本身不会提取或验证库存。

`withCyclePhase(plan, state)` 会用已派发工作校验待返还表：已经完成的步骤计入全部次数，当前步骤只计入 `step.crafts() - state.remainingCrafts()`，未来步骤不计入。每个待返还键都必须能由这些产物解释，数量为正且不能超过其产量总和。`phase()` 为 null 时待返还表必须为空。旧构造器会主动丢弃阶段信息与待返还条目，而非恢复新协议；不能用它们加载启用了阶段执行的存档订单。

返回产物应随时计入 CPU 可用库存，最终产物用 `amountToRetain` 保留后续循环需要的部分。批量取当前全部输入能支持的次数、`remainingCrafts()` 和提供器安全上限的最小值；每次用实际派发次数推进，不固定为初始种子批量，也不追加未规划工作。

在 `PRESERVE_MINIMUM` 下，规划器已经为“完整下单产物 + 最小启动种子向量”安排了足够的循环产量。CPU 应把该向量留在内部库存直到订单结束，再由正常清理流程回存 ME；不能用保留量冲抵或缩减请求方的最终产物。

对于可能在 `pushPattern` 内同步返回产物的 Provider，应在调用前暂时推进控制器；调用拒绝或抛出异常时恢复之前的快照，避免同步产物按过期的循环步骤判断。

不能只实现标记接口。忽略执行顺序、受保护输入、最终产物暂存或状态持久化的 CPU 仍可能卡死。

内置原版与量子 CPU 接入支持 Data Energistics 已标记订单包裹的无实体输出结算，适用于自动规划和直接 API 下单。量子 CPU 只为成功派发后实际登记的包裹产量创建完成记录，原版 CPU 会校正 DE 原有记录的批量计数。虚拟包裹结算后仍须满足循环阶段结束条件；手动取消不受该等待条件影响。独立 CPU 若自行实现虚拟产物，应沿用 DE 的语义，不能用实体包裹或请求方实际接收量代替完成记录。

### 公共 CPU 接入辅助方法

独立 CPU 可通过 `AelisCycleExecutionApi` 完成规范化、受保护输入提取及持久化，无需导入 `runtime` 中的内部实现：

| 方法 | 约定 |
|---|---|
| `ICraftingPlan preparePlan(ICraftingPlan plan)` | 返回应提交的计划，规范化循环数量包装并保留、准备元数据；必须使用返回值 |
| `Map<AEKey, Long> getCyclicCraftAmounts(ICraftingPlan plan)` | 不可修改的循环材料数量；无数据时为空，不等同于运行时待返还表 |
| `ICraftingInventory guardInputs(AelisCycleRuntimeController runtime, AEKey patternDefinition, ICraftingInventory inventory)` | 允许 runtime 为 null，此时返回原库存；返回 null 表示禁止派发。整次提取、追加批量输入和回滚均须使用返回的库存 |
| `long dispatchedCrafts(AelisCycleRuntimeController runtime, AEKey patternDefinition, KeyCounter[] inputs)` | 当前循环步骤的聚合输入实际代表多少次执行，非当前步骤返回 `0`。数组与输入容器不能为 null，各受保护输入必须对应相同的完整正数次数，且不超过步骤剩余量 |
| `CompoundTag writeRuntime(AelisCycleRuntimeController runtime, HolderLookup.Provider registries)` | 序列化非 null 控制器的计划、阶段和待返还状态 |
| `Optional<AelisCycleRuntimeController> readRuntime(CompoundTag tag, HolderLookup.Provider registries)` | 恢复支持的运行时格式，包括旧 v1。空值表示状态缺失、无效或版本不支持；原本保存了循环元数据的订单不能静默当普通订单继续运行 |

应在构造 CPU 任务表之前调用 `preparePlan`；仅调用 `getPlan` 不会替换该任务表。受保护库存只属于本次提取尝试，不能跨样板或跨运行时推进缓存复用。`dispatchedCrafts` 本身不推进控制器。没有受保护输入的步骤是合法的，但该辅助方法无法推导其实际执行次数；宿主必须自己确定次数，将其限制在 `remainingCrafts()` 内，再调用 `patternDispatched`。

```java
plan = AelisCycleExecutionApi.preparePlan(plan);
var runtime = AelisCycleExecutionApi.getPlan(plan)
        .map(AelisCycleRuntimeController::withCyclePhase)
        .orElse(null);

// 仅在 CPU 仍拥有对应订单时保存。
if (runtime != null) {
    jobTag.put("cycleRuntime",
            AelisCycleExecutionApi.writeRuntime(runtime, registries));
}

// 标签存在却无效时，必须进入接入方的安全订单处理流程。
if (jobTag.contains("cycleRuntime")) {
    var restored = AelisCycleExecutionApi.readRuntime(
            jobTag.getCompound("cycleRuntime"), registries);
    if (restored.isEmpty()) {
        rejectOrCancelSavedJobSafely(); // 接入方实现，保留或安全返还库存。
        return;
    }
    runtime = restored.get();
}
```

每次插入前保留该订单待返回库存的引用，因为宿主插入代码可能直接结束订单。真实插入后，将 `max(0, min(offeredAmount, waitingBefore - waitingAfter))` 交给 `recordReturned`，不要登记模拟插入或重复登记同一返回量。这些辅助方法不自动安装 CPU 回调、提交订单或实现第三方虚拟产物结算。

## 3. 无限存储标记

### 物品标签

普通物品型存储元件优先使用公共标签：

```text
#appliedenhancements:infinite_storage_cells
```

在接入模组中创建：

```text
src/main/resources/data/appliedenhancements/tags/item/infinite_storage_cells.json
```

示例：

```json
{
  "replace": false,
  "values": [
    "examplemod:infinite_item_cell",
    "examplemod:infinite_fluid_cell"
  ]
}
```

Java 中可以复用标签标识或查询物品：

```java
TagKey<Item> tag = InfiniteStorageCells.ITEM_TAG;
boolean marked = InfiniteStorageCells.isMarked(stack);
```

`isMarked` 对 null 或空物品返回 false。运行时标记接口没有方法，必须由存储库存本身实现；只在磁盘物品类型上实现不会标记已挂载库存。

KubeJS 也可以写入该物品标签：

```javascript
ServerEvents.tags('item', event => {
  event.add('appliedenhancements:infinite_storage_cells', [
    'examplemod:infinite_item_cell'
  ])
})
```

### 运行时标记

无法稳定关联回物品的特殊 AE2 `StorageCell` 实现，可以同时实现：

```java
public final class ExampleInfiniteInventory
        implements StorageCell, InfiniteStorageCellMarker {
    // StorageCell 实现省略。
}
```

标记只告诉 Applied Enhancements：这个实现本身已经提供无限内容，应使用无限数量哨兵和 `9.2E` 显示。它不会把有限磁盘改造成真正的无限来源。

## 4. 第三方编码样板与样板筛选

### 注册产物解析器

在 Common Setup 的 `enqueueWork` 中注册一次：

```java
event.enqueueWork(() -> PatternDuplicateApi.registerOutputResolver(
        ResourceLocation.fromNamespaceAndPath("examplemod", "custom_patterns"),
        100,
        (patternStack, level) -> {
            if (!isExamplePattern(patternStack)) {
                return List.of();
            }
            return List.of(resolvePrimaryOutput(patternStack, level));
        }));
```

解析规则：

- 优先级越高越早执行；优先级相同时按注册 ID 排序。
- 同一个 `ResourceLocation` 只能注册一次。
- 返回空列表表示“不处理这个物品”，API 会继续调用下一个解析器。
- 第一个 `AEKey` 是重复分组使用的主产物。
- 返回值不包含数量；重复判断故意忽略最终产量。
- 解析器异常会被记录并跳过，随后继续其他解析器或 AE2 原生解码。

### 直接使用重复与失效检测

```java
List<PatternDuplicateApi.PatternEntry> entries = collectEntries();

Map<PatternSlotRef, AEKey> outputs =
        PatternDuplicateApi.indexPrimaryOutputs(entries, level);

Set<PatternSlotRef> duplicateSlots =
        PatternDuplicateApi.findDuplicateSlots(outputs);

Set<PatternSlotRef> invalidSlots =
        PatternDuplicateApi.findInvalidSlots(entries, level);
```

也可以使用任意稳定键调用泛型重载：

```java
Set<PatternSlotRef> duplicates =
        PatternDuplicateApi.findDuplicateSlots(customSlotToKeyMap);
```

`PatternSlotRef.containerId()` 是 AE2 为机器容器分配的服务端 ID，不是菜单槽位索引。该引用只应在当前终端保持打开期间使用。

其构造器为 `PatternSlotRef(long containerId, int slot)`，要求 `slot` 非负。构造记录本身不保证容器或槽位存在，也不代表玩家有访问权限。

`PatternDuplicateApi.isInvalidPattern(...)` 用于判断 AE2 与已注册第三方产物解析器均无法解析的编码样板；`findInvalidSlots(...)` 可批量返回对应槽位。

直接查询方法包括 `resolveOutputs(ItemStack, Level)`、`primaryOutput(ItemStack, Level)`、`isInvalidPattern(ItemStack, Level)`、`outputMatchesSearch(ItemStack, Level, String)`。`registeredOutputResolvers()` 返回注册快照。`findDuplicateSlots` 接收 `(Collection<PatternEntry>, Level)` 或 `Map<PatternSlotRef, K>`；`findInvalidSlots`、`indexPrimaryOutputs` 接收 `(Collection<PatternEntry>, Level)`。`PatternEntry(PatternSlotRef, ItemStack)` 保留传入物品引用，需要长期快照时应自行复制可变的 `ItemStack`。null、空物品或 null 世界不会解析出产物；普通非样板物品不会自动判为失效。搜索对产物显示名匹配，并使用 `Locale.ROOT` 规范化过滤字符串。

## 5. 兼容样板终端注册

在 Client Setup 中注册精确的客户端界面类名：

```java
event.enqueueWork(() -> PatternTerminalIntegrationApi.register(
        ResourceLocation.fromNamespaceAndPath("examplemod", "pattern_terminal"),
        PatternTerminalIntegrationApi.Family.AE2_PATTERN_ACCESS,
        "examplemod.client.gui.ExamplePatternAccessScreen"));
```

可用布局族：

| `Family` | 约束 |
|---|---|
| `AE2_PATTERN_ACCESS` | 必须继承 AE2 样板管理终端并保留其槽位行与机器标题布局 |
| `EXTENDEDAE_PATTERN_ACCESS` | 必须继承 ExtendedAE 扩展样板终端并保留其对应布局 |

注册成功的兼容终端会获得重复筛选、失效样板筛选、快速移动、框选、右键菜单和机器组剪切/粘贴控件。“重复”“失效”和“移动”三种模式互斥。

AE2 系列界面会继承内置样板管理终端的独立第二行工具栏，避免遮挡本地化标题与搜索框；ExtendedAE 系列界面保留原有顶部控件布局。

内置注册已经覆盖：

- AE2 样板管理终端；
- AE2WTLib 无线样板管理终端；
- ExtendedAE 扩展样板管理终端；
- ExtendedAE 无线扩展样板管理终端。

完全自定义的行模型不能仅靠注册自动获得功能，应直接使用 `PatternDuplicateApi`、`PatternBatchMoveApi` 和 `PatternQuickMoveSession` 实现自己的界面层。

注册 ID 必须唯一，界面名必须是精确运行时类名。`registrations()` 返回快照；`supports(Object screen, Family)` 与 `supports(String screenClassName, Family)` 查询精确注册。注册父类不会自动注册其全部子类。

## 6. 原子批量移动样板

### 服务端菜单直接实现接口

```java
public final class ExamplePatternMenu extends AbstractContainerMenu
        implements PatternBatchMoveApi.MenuExtension {

    @Override
    public PatternBatchMoveApi.Result movePatterns(
            ServerPlayer player,
            PatternBatchMoveApi.Request request) {
        // 1. 验证玩家仍有权访问当前终端。
        // 2. 重新读取并验证每个来源样板。
        // 3. 验证目标机器、目标槽和容量。
        // 4. 先保存全部快照，再一次性提交。
        // 5. 任意失败都恢复全部来源和目标。
        return PatternBatchMoveApi.Result.success(movedCount);
    }
}
```

### 为不能实现接口的菜单注册处理器

```java
event.enqueueWork(() -> PatternBatchMoveApi.registerMenuHandler(
        ResourceLocation.fromNamespaceAndPath("examplemod", "pattern_menu"),
        100,
        new PatternBatchMoveApi.MenuHandler() {
            @Override
            public boolean supports(AbstractContainerMenu menu) {
                return menu instanceof ExamplePatternMenu;
            }

            @Override
            public PatternBatchMoveApi.Result movePatterns(
                    ServerPlayer player,
                    AbstractContainerMenu menu,
                    PatternBatchMoveApi.Request request) {
                return executeAtomicMove(player, (ExamplePatternMenu) menu, request);
            }
        }));
```

菜单自身的 `MenuExtension` 优先于已注册处理器。处理器优先级越高越早检查，同优先级按 ID 排序。第一个 `supports(menu)` 返回 `true` 的处理器负责请求，其失败不会继续尝试其他处理器。ID 必须唯一，`registeredMenuHandlers()` 返回注册快照。

### 客户端发起请求

```java
PatternBatchMoveApi.requestMove(
        menu.containerId,
        selectedSources,
        targetContainerIds,
        preferredTargetSlot);
```

字段与限制：

| 字段 | 含义 |
|---|---|
| `sources` | 来源 `PatternSlotRef`，数量 `1..512` |
| `targetContainerIds` | 候选目标机器容器 ID，数量 `1..128` |
| `preferredTargetSlot` | 首选目标槽；`-1` 表示自动选择 |

客户端数据不可信。服务端处理器必须重新验证菜单 ID、玩家权限、来源内容、目标容量和同源目标，并保证全有或全无。`PatternBatchMoveApi` 会捕获处理器运行时异常并返回 `APPLY_FAILED`，但自定义处理器自己的库存回滚仍由接入方负责。

内置 `PatternAccessTermMenu` 处理器会在规划事务前通过 `PatternDuplicateApi.isInvalidPattern(...)` 跳过失效编码样板：这些样板留在原槽位，其余有效来源仍按事务一次性提交。自定义处理器如需一致行为，也应采用同样策略。

`execute(ServerPlayer, Request)` 在服务端同步返回 `Result`。`Request(int menuId, List<PatternSlotRef> sources, List<Long> targetContainerIds, int preferredTargetSlot)` 复制两份列表，要求菜单 ID 非负、首选槽至少为 `-1`；`copySources(Collection)` 与 `copyTargets(Collection)` 公开相同的非空和数量校验。`Failure` 包括 `NONE`、`INVALID_MENU`、`INVALID_SOURCE`、`INVALID_TARGET`、`SAME_TARGET`、`NOT_ENOUGH_SPACE`、`APPLY_FAILED`。失败结果必须移动零个样板，建议使用 `Result.success(...)` / `Result.failure(...)` 工厂。内置请求载荷会将服务端结果显示为系统消息，`requestMove(...)` 自身返回 void。

## 7. 自定义客户端快速移动会话

`PatternQuickMoveSession` 仅能在客户端代码中引用。每个打开的终端界面创建一个实例：

```java
private final PatternQuickMoveSession quickMove = new PatternQuickMoveSession();

@Override
public void onClose() {
    quickMove.clear();
    super.onClose();
}
```

典型输入流程：

1. `setEnabled(true)` 开启移动模式。
2. 鼠标按下时调用 `beginSelection(...)`。
3. 拖动时调用 `drag(...)`。
4. 松开时调用 `finishSelection(...)`。
5. 绘制阶段调用 `renderSelectionBox(...)` 与 `renderSelectedSlots(...)`。
6. 剪切时调用 `cutSelectedPattern(...)` 或 `cutGroup(...)`。
7. 粘贴时调用 `paste(...)`，由公共网络 API 发往服务端。
8. 关闭界面或切换到互斥模式时调用 `setEnabled(false)` 或 `clear()`。

`PatternQuickMoveSession` 不直接修改服务端库存。界面层仍需正确维护“显示槽位到真实来源槽位”的 `Map<PatternSlotRef, PatternSlotRef>`。

关闭模式会清除选择与剪切缓存。`clear()` 只清除缓存状态，不改变 `enabled()`；退出快速移动模式时使用 `setEnabled(false)`。普通框选替换之前的选择，`beginSelection(..., true)` 从已有选择中减选，普通单击切换一个槽位。超过批量移动限制时不会提交超限请求。

`enabled()`、`selectedCount()`、`cutCount()`、`hasCutBuffer()`、`isSelected(PatternSlot, Map<PatternSlotRef, PatternSlotRef>)` 可查询会话状态。`cutGroup(Collection<PatternContainerRecord>)` 返回缓存数量；`paste(int, List<Long>, int)` 只表示是否发出请求，不代表服务端已提交事务。`finishSelection` 与绘制方法虽然接收 `Collection<Slot>`，实际可选择项仍须为 AE2 `PatternSlot`。鼠标与选择框使用屏幕坐标；槽位覆盖层使用菜单局部坐标，需配合界面的 GUI 平移。

## 8. 网络物品右键菜单扩展

在 Client Setup 中注册菜单项提供器：

```java
event.enqueueWork(() -> NetworkItemContextMenuApi.register(
        ResourceLocation.fromNamespaceAndPath("examplemod", "inspect_item"),
        100,
        context -> {
            if (!context.key().getId().getNamespace().equals("examplemod")) {
                return List.of();
            }
            return List.of(new NetworkItemContextMenuApi.Entry(
                    Component.literal("复制并检查"),
                    NetworkItemContextMenuApi.Context::copyId));
        }));
```

提供器每次打开菜单时执行，可以根据 `key()`、`storedAmount()`、`requestableAmount()` 与 `craftable()` 决定是否返回条目。优先级高的注册先执行，优先级相同时按注册 ID 排序；重复 ID 会被拒绝，提供器运行时异常会记录后跳过。

`EntryProvider.createEntries(Context)` 返回条目列表，null 列表和条目会被跳过。`Entry(Component label, Consumer<Context> action)` 要求两个参数非 null，`activate(Context)` 执行客户端动作。`registrations()` 返回注册快照，`entries(Context)` 重新构建不可修改的自定义条目列表。Context 只属于当前菜单调用，不应长期保存并当作服务端库存句柄。

`Context` 提供 `extractOne()`、`extractStack()`、`extractAmount(long)`、`requestCraft()`、`copyName()`、`copyId()` 和 `searchSameMod()`。第三方自定义服务端动作仍必须使用自己的网络载荷，并重新验证玩家当前菜单、权限、资源键和数量，不能信任注册在客户端的菜单项。

## 9. Provider 调度批次回调

需要感知 AE2 合成 CPU 调度批次的 Provider，可以在实际 Provider 实例上实现：

```java
public final class ExampleProvider implements MolecularBalancedBatchProvider {
    private KeyCounter[] currentBatch;

    @Override
    public void appliedenhancements$beginBalancedBatch(KeyCounter[] firstInputs) {
        currentBatch = firstInputs;
        // 创建批次队列或事务上下文。
    }

    @Override
    public void appliedenhancements$endBalancedBatch() {
        try {
            flushBatch();
        } finally {
            currentBatch = null;
        }
    }
}
```

语义保证：

- 同一次 AE2 CPU 调度中，第一次 `pushPattern` 前调用开始回调。
- 同一 Provider 的后续推送属于同一批次。
- 每次成功开始都会配对一次结束，包括推送或外层调度异常退出。
- `firstInputs` 是第一次尝试输入的防御性快照。
- 开始回调不表示第一次推送一定会被 Provider 接受。

默认的 `appliedenhancements$beginAdaptiveBatch(...)` 会转发到 `appliedenhancements$beginBalancedBatch(...)`，现有实现无需额外覆盖。

内置批次回调作用域目前属于 AE2 原生 CPU。AdvancedAE 量子 CPU 的循环支持不代表同时提供这套独立的批次回调。独立 CPU 若要支持该接口，应明确实现同样的开始/结束配对；Provider 实现接口本身不会提高批量大小或启用智能倍增。实现方应在 `finally` 中清理批次状态，批次结束后如需保留输入快照，应自行复制。

## KubeJS 边界

KubeJS 仅支持通过物品标签标记无限磁盘。以下能力没有 KubeJS API：

- AELIS 会话调用；
- 编码样板 Java 解析器注册；
- 样板终端界面注册；
- 网络物品右键菜单项注册；
- 原子批量移动处理器；
- Provider 批次回调。

这些能力涉及 AE2 Java 类型、客户端界面或服务端事务验证，应由 Java 模组接入。

## 发布前检查清单

`1.0.4` 的单元测试及隔离运行结果见 [发行验证范围](../README_ZH.md#验证范围)。独立运行验证环境和依赖模组自带的 GameTest 不属于仓库默认单元测试；独立 CPU 仍需验证自己的接入边界。

- [ ] 只从稳定 API 包导入类型。
- [ ] 可选兼容代码已隔离，缺少 Applied Enhancements 时不会触发类加载。
- [ ] 客户端 API 只在客户端类和 Client Setup 中使用。
- [ ] 注册 ID 使用接入方自己的命名空间且不会重复。
- [ ] AELIS 实例没有跨计算或跨线程共享。
- [ ] 普通回退时继续自己的规划器或 AE2 原生路径。
- [ ] 批量移动处理器在服务端重新验证所有客户端字段。
- [ ] 批量移动失败能恢复所有来源和目标槽。
- [ ] `PatternQuickMoveSession` 在关闭界面时清除。
- [ ] 网络物品菜单的自定义服务端动作重新验证所有客户端字段。
- [ ] 已在 AE2 `19.2.17` 和目标整合包中完成客户端与服务端验证。
