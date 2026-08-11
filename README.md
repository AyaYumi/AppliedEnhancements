# Applied Enhancements

Applied Enhancements 是面向 Applied Energistics 2（AE2）的 NeoForge 增强模组。它不添加方块或物品，主要提供 Long 范围合成、合成计算进度、样板缓存、材料汇总修正及可选的 MAX_FAST 规划路径。

## 兼容版本

| 项目 | 当前版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.220（开发环境）；元数据允许 21.1.0 及以上 |
| Applied Energistics 2 | 19.2.17（精确版本依赖） |
| Java | 21 |

AE2 版本被固定为 `19.2.17`，因为核心功能依赖该版本的 Mixin 注入点。升级 AE2 前应重新执行编译和运行期 Mixin 验证。

## 当前功能

| 功能 | 行为 |
|---|---|
| Long 范围合成 | 合成数量使用 `long` 传输和规划；默认上限 1 万亿，可配置至 `Long.MAX_VALUE` |
| 精确数量输入 | 使用 `BigDecimal.longValueExact()` 语义，拒绝溢出、非法小数和回绕值 |
| 合成计算进度 | 确认界面显示计算阶段、耗时、已处理步骤以及已知总量的进度条 |
| 计算路径标识 | 区分 AE2 原生、MAX_FAST、AE2 回退及 EcoAE 路径 |
| 样板缓存 | 对 AE2 样板输入有效性和容器物品结果做有界实例缓存 |
| 材料汇总修正 | 对超大合成计划使用饱和算术，修正预览中的存储、合成和缺失数量 |
| 无限存储显示 | 可抽取量超过自报库存的存储元件在 ME 终端和元件预览中显示为 `9.2E`；网络汇总采用饱和加法 |
| 无限 CPU 显示 | 对特殊存储容量和并行度使用紧凑显示，避免界面整数溢出 |
| AE2WTLib 兼容 | 使用 `@Pseudo` 可选目标；未安装时跳过，且不会提前加载目标类 |

## Long 范围合成

合成数量界面允许最多 20 位输入。客户端先进行精确解析，服务端再校验功能开关和最大订单数量。以下输入不会被转换为其他合法数量：

```text
18446744073709551617
9223372036854775807 + 1
```

默认最大订单为：

```text
1,000,000,000,000
```

将 `max_crafting_order_amount` 调高后，理论上可接受至：

```text
9,223,372,036,854,775,807
```

实际能否完成如此大的计划仍取决于 AE2 网络、配方图、内存和执行时间。

## MAX_FAST 规划器

MAX_FAST 会尝试编译并批量执行兼容的配方图；多候选、可复用输入、模糊输入和副产物节点作为局部边界接收聚合请求，并由 AE2 原生逻辑处理，不会迫使整张图改用递归事务执行。遇到无法隔离的不支持结构时，`SAFE` 模式回退到 AE2 原生规划。已编译图只在单次计算会话内复用，不存在跨 Grid 的全局图缓存。

| 模式 | 说明 |
|---|---|
| `OFF` | 完全关闭 MAX_FAST，使用 AE2 原生规划 |
| `SAFE` | 默认值；保守检查边界与兼容性，不适用时回退 AE2 |
| `AGGRESSIVE` | 放宽部分兼容性检查，可能产生硬失败，只建议排障或受控环境使用 |

当前实现没有全局图缓存、并行图执行、智能候选模块或配方预编译。旧迁移记录中出现的这些选项不属于当前配置。

启用自动退让且检测到 `ecoae` 时，如果本规划器优先级数值高于约定的 EcoAE 优先级 `50`，MAX_FAST 会停用。优先级数值越小，优先级越高。

## 配置

首次启动后会生成两个配置文件。

### `config/appliedenhancements-common.toml`

```toml
[crafting]
max_crafting_order_amount = 1000000000000

[caching]
enable_pattern_caching = true
pattern_cache_size = 32

[crafting_plan]
enable_enhanced_material_calculation = true
```

### `config/appliedenhancements-maxfast.toml`

```toml
[features]
enableLongRangeCrafting = true
enableProgressDisplay = true

[maxfast]
enableMaxFastPlanner = true
maxFastMode = "SAFE"
maxFastMaxNodes = 100000
maxFastCompileBudgetMs = 2000
maxFastPlannerPriority = 500
maxFastAutoYield = true

[debug]
maxFastDiagnostics = false
```

`maxFastDiagnostics` 会产生较多日志，仅应在定位规划回退或兼容问题时临时开启。

## 构建与验证

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat build --no-daemon --console=plain
.\gradlew.bat runGameTestServer --no-daemon --console=plain
```

单元测试覆盖网络路径 ID、进度状态边界、Long 精确解析和饱和算术。`runGameTestServer` 可验证启动过程中实际被类加载的服务端 Mixin；仓库目前没有场景化 GameTest，因此该任务可能以“没有测试函数”退出，也不会覆盖完整的 MAX_FAST 配方执行。

客户端 Mixin 还应通过 `runClient` 手工验证以下流程：

1. 输入普通数量并进入合成确认界面。
2. 输入大于 `Integer.MAX_VALUE` 的合法数量并进入确认界面。
3. 输入超出 `Long.MAX_VALUE` 的值，确认按钮必须保持禁用。
4. 分别关闭 Long 合成和进度显示开关，确认原生流程仍可用。

## 开发者接口

项目保留以下扩展接口：

- `MolecularBalancedBatchProvider`：在一次 AE2 合成 CPU 调度中，首次向实现该接口的 Provider 推送配方前调用 `beginAdaptiveBatch`，调度结束（含异常退出）时配对调用 `endBalancedBatch`。
- `InfiniteConstants`：无限存储和并行度常量。
- `LongCraftingAmountMenuBridge`、`LongCraftingConfirmMenuBridge`：Long 合成菜单桥接。
- `OmniCalculationPathCarrier`：规划结果路径传递。

## 文档说明

[MAXFAST_MIGRATION_COMPLETE.md](MAXFAST_MIGRATION_COMPLETE.md) 记录当前 MAX_FAST 的落地范围和限制。配置与兼容性结论以本 README、源码和自动化测试为准。

## 许可证

本项目使用 [MIT License](LICENSE)。
