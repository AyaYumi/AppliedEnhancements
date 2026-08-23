# Applied Enhancements API 接入文档

[English documentation](API_INTEGRATION.md)

本文面向希望接入 Applied Enhancements `1.0.0` 的 NeoForge 模组作者，涵盖依赖声明、稳定 API、注册生命周期、客户端/服务端边界和失败回退要求。

## 兼容基线

| 组件 | 最低版本 | 说明 |
|---|---:|---|
| Minecraft | `1.21.1` | 精确游戏版本 |
| Java | `21` | 编译与运行目标 |
| NeoForge | `21.1.220` | 与当前发行版一致 |
| Applied Energistics 2 | `19.2.17` | 公共接口直接引用 AE2 类型 |
| Applied Enhancements | `1.0.0` | 本文档对应版本 |

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
    compileOnly files("libs/appliedenhancements-1.0.0.jar")

    // 只有需要在开发运行环境中联调时才添加。
    runtimeOnly files("libs/appliedenhancements-1.0.0.jar")
}
```

如果接入代码会无条件加载公共 API，应在 `neoforge.mods.toml` 中声明硬依赖：

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
type="required"
versionRange="[1.0.0,)"
ordering="AFTER"
side="BOTH"
```

如果只在检测到本模组时加载独立兼容类，可以声明可选依赖：

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
type="optional"
versionRange="[1.0.0,)"
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

| API | 侧别 | 推荐注册/调用阶段 | 用途 |
|---|---|---|---|
| `MaxFastCraftingPlanner` | 服务端 | 每次 AE2 合成计算 | 主动调用 MAX_FAST 规划器 |
| `InfiniteStorageCellMarker` | 双端 | 运行时类型实现 | 标记运行时无限存储实现 |
| `InfiniteStorageCells` | 双端 | 数据包或运行时查询 | 公共无限磁盘物品标签 |
| `PatternDuplicateApi` | 双端可用 | Common Setup 注册 | 解析产物并查找重复样板 |
| `PatternOutputResolver` | 双端可用 | Common Setup 注册 | 解析第三方编码样板产物 |
| `PatternTerminalIntegrationApi` | 客户端 | Client Setup 注册 | 接入兼容的样板终端界面 |
| `PatternBatchMoveApi` | 客户端与服务端 | Common Setup 注册服务端处理器 | 原子批量移动样板 |
| `PatternQuickMoveSession` | 仅客户端 | 每个打开的界面创建 | 管理框选、剪切缓存和覆盖层 |
| `PatternSlotRef` | 双端 | 当前终端会话内 | 稳定标识机器容器和样板槽 |
| `MolecularBalancedBatchProvider` | 服务端 | Provider 类型实现 | 接收一次 CPU 调度批次的开始/结束回调 |

## 1. MAX_FAST 规划器

### 基本规则

- 本模组的自动规划接入默认关闭，但其他 Mod 调用公共 API 不受 `enableAutomaticMaxFastPlanner` 影响。
- 每次 AE2 合成计算创建一个 `MaxFastCraftingPlanner` 实例。
- 同一计算的真实尝试与模拟尝试可以复用该实例。
- 实例不是线程安全的，不得跨计算根节点或并行线程共享。
- 该 API 使用 AE2 内部合成树类型，因此调用时必须确保 Applied Enhancements 已加载并且对应 Mixin 已生效。

### 创建规划器

使用服务端配置中的节点数和编译时间预算：

```java
MaxFastCraftingPlanner planner = MaxFastCraftingPlanner.createConfigured(
        MaxFastCraftingPlanner.NO_PAUSE,
        MaxFastCraftingPlanner.ProgressListener.NONE);
```

使用接入方自己的预算和进度回调：

```java
MaxFastCraftingPlanner planner = MaxFastCraftingPlanner.create(
        100_000,
        2_000,
        () -> {
            // 可选的协作暂停点；需要取消时抛出 InterruptedException。
        },
        new MaxFastCraftingPlanner.ProgressListener() {
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

```java
MaxFastCraftingPlanner.Result result = planner.tryExecute(
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
    // MAX_FAST 已把本次请求应用到传入的模拟库存和缺失计数。
    return;
}

if (result.shouldFallback()) {
    // API 已恢复 missingItems 和候选状态，可以安全调用自己的规划器或 AE2 原生路径。
    runNativePlanner();
}
```

`Result` 中的 `fallbackReason`、`error`、节点统计和耗时用于诊断，不应把某个具体回退字符串当成稳定协议。`branchFailure` 非空时不属于普通兼容回退。

## 2. 无限存储标记

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

## 3. 第三方编码样板与重复产物

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

### 直接使用重复检测

```java
List<PatternDuplicateApi.PatternEntry> entries = collectEntries();

Map<PatternSlotRef, AEKey> outputs =
        PatternDuplicateApi.indexPrimaryOutputs(entries, level);

Set<PatternSlotRef> duplicateSlots =
        PatternDuplicateApi.findDuplicateSlots(outputs);
```

也可以使用任意稳定键调用泛型重载：

```java
Set<PatternSlotRef> duplicates =
        PatternDuplicateApi.findDuplicateSlots(customSlotToKeyMap);
```

`PatternSlotRef.containerId()` 是 AE2 为机器容器分配的服务端 ID，不是菜单槽位索引。该引用只应在当前终端保持打开期间使用。

## 4. 兼容样板终端注册

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

注册成功的兼容终端会获得重复筛选、快速移动、框选、右键菜单和机器组剪切/粘贴控件。重复模式与移动模式互斥。

内置注册已经覆盖：

- AE2 样板管理终端；
- AE2WTLib 无线样板管理终端；
- ExtendedAE 扩展样板管理终端；
- ExtendedAE 无线扩展样板管理终端。

完全自定义的行模型不能仅靠注册自动获得功能，应直接使用 `PatternDuplicateApi`、`PatternBatchMoveApi` 和 `PatternQuickMoveSession` 实现自己的界面层。

## 5. 原子批量移动样板

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

处理器优先级越高越早检查。第一个 `supports(menu)` 返回 `true` 的处理器负责该请求。

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
| `sources` | 来源 `PatternSlotRef`，最多 `512` 个 |
| `targetContainerIds` | 候选目标机器容器 ID，最多 `128` 个 |
| `preferredTargetSlot` | 首选目标槽；`-1` 表示自动选择 |

客户端数据不可信。服务端处理器必须重新验证菜单 ID、玩家权限、来源内容、目标容量和同源目标，并保证全有或全无。`PatternBatchMoveApi` 会捕获处理器运行时异常并返回 `APPLY_FAILED`，但自定义处理器自己的库存回滚仍由接入方负责。

## 6. 自定义客户端快速移动会话

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

## 7. Provider 调度批次回调

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

## KubeJS 边界

KubeJS 仅支持通过物品标签标记无限磁盘。以下能力没有 KubeJS API：

- MAX_FAST 会话调用；
- 编码样板 Java 解析器注册；
- 样板终端界面注册；
- 原子批量移动处理器；
- Provider 批次回调。

这些能力涉及 AE2 Java 类型、客户端界面或服务端事务验证，应由 Java 模组接入。

## 发布前检查清单

- [ ] 只从稳定 API 包导入类型。
- [ ] 可选兼容代码已隔离，缺少 Applied Enhancements 时不会触发类加载。
- [ ] 客户端 API 只在客户端类和 Client Setup 中使用。
- [ ] 注册 ID 使用接入方自己的命名空间且不会重复。
- [ ] MAX_FAST 实例没有跨计算或跨线程共享。
- [ ] 普通回退时继续自己的规划器或 AE2 原生路径。
- [ ] 批量移动处理器在服务端重新验证所有客户端字段。
- [ ] 批量移动失败能恢复所有来源和目标槽。
- [ ] `PatternQuickMoveSession` 在关闭界面时清除。
- [ ] 已在 AE2 `19.2.17` 和目标整合包中完成客户端与服务端验证。
