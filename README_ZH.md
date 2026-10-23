# Applied Enhancements

[English](README.md) · [接口文档](docs/API_INTEGRATION_ZH.md) · [配置参考](CONFIGURATION.md)

当前 **1.1.4-forge** 为 Minecraft 1.20.1 / Forge 提供 AE2 规划、存储访问和样板终端
增强。本版增加共享批量投料事务，让循环 CPU 的首份抽料、额外份数、回滚和原生
Provider 钩子保持同一材料账本。

可恢复的规划校验与可选显示接口错误改为原生回退或恢复完整原始任务。批次换算、
元数据挂接、进度回调与范围清理保留这些恢复路径。AE2 Crafting Tree（AE2CT）1.1.1
兼容修复会在异步建树前挂接精确数量；真实缺料仍通过 AE2 的正常缺料结果反馈。

## 功能

| 领域 | 当前行为 |
| --- | --- |
| 合成订单 | 原生、long 上限、大整数三种模式；精确数量输入，传输上限为 1,048,576 字符 |
| AELIS 规划 | 单次会话缓存、精确材料/字节元数据、循环求解和受限安全回退 |
| 计划确认 | 精确材料列、循环数量、筛选搜索、交互式依赖图及可选 EMI 收藏／JEI 缺料书签 |
| 循环执行 | 共享保护库存视图、当前阶段次数上限、拒收回滚与产物回收 |
| 手动确认 | 多个确认页之间预留有限材料；明确的无限来源不参与有限锁定 |
| 存储性能 | 样板与容器缓存、存储总线候选槽索引、输入输出总线提示 |
| 无限元件 | 标签、运行标记和明确兼容元件；不凭巨大有限数值推断无限 |
| 样板终端 | 重复/失效筛选、框选、原子剪切粘贴、快速移动接入 |
| 物品菜单 | ME 网络操作及可选 JEI 配方、搜索、抽取、作弊权限联动 |
| 日志 | 无直接控制台输出或 INFO/DEBUG/TRACE 日志；必要 WARN/ERROR 按类别及严重程度全局限流 |

## 安装

需要 Java 17、Minecraft 1.20.1、Forge 47.4.10+（构建基线为 47.4.20）。
同一个通用 JAR 兼容标准 AE2 15.4.10 和 UELM 15.5.4；实例中选择其中一种 AE2 实现，
Applied Enhancements 使用相同文件。ExtendedAE、AE2WTLib、JEI、EMI、AdvancedAE 和其他
附属兼容均为可选；新版附属的兼容性取决于其内部接口。

构建 `build/libs/appliedenhancements-1.1.4-forge.jar` 后放入两端 mods，客户端和
服务端使用相同构建，内部载荷协议为 `1.1.4-forge-5`，升级时须同时更新两端。附属使用独立安装的公开 API，不嵌入本模组。

## 配置与操作

配置位于正在使用实例的 `config/appliedenhancements-common.toml`。
订单接管、进度显示、增强材料预览和自动 AELIS 默认关闭；缓存与存储优化默认开启。
完整 [18 项配置](CONFIGURATION.md) 描述分组、默认值、范围和迁移。

样板快速移动默认右键，物品操作默认 Alt＋右键，两项独立绑定且只在 GUI 内生效。
服务端重新验证权限、源物品和容量。JEI 给予物品沿用 JEI 的同步作弊权限。

选择数量后直接打开自定义计划确认界面，在其中显示计算和重新规划进度；最终结果若
不是 AELIS 则回到 AE2 原版视图，也可手动切回原版。缺失材料高亮显示，
缺料收藏优先加入 EMI 收藏，EMI 不可用时使用 JEI 书签；两者均为可选依赖。
安装 EMI 时，新确认界面也显示其侧栏和搜索框，不受合成确认菜单没有物品槽位的限制。
表格列标题与数量共用居中位置，材料名称保持左对齐。开始按钮不可用时按住 Shift 可提交等待补料的订单，
仍需可用 CPU 及足够的合成存储容量，且每种缺失材料数量必须在有符号 `long` 范围内
（不超过 `Long.MAX_VALUE`）；不会生成缺失材料或绕过服务端提交检查。
新界面默认开启；将客户端通用配置中的 `crafting.aelis.enable_plan_screen` 设为 `false`
即可保留 AE2 原版确认界面，不影响规划与合成执行。界面翻译键为 `enable_aelis_plan_screen`。

“材料列表”和“合成树”使用同一计划。拖动节点调整位置，拖动背景平移，滚轮或按钮缩放，
点击循环分组标题折叠／展开。筛选搜索保留相关分支；节点文字自动换行，紧凑布局提供
“图示说明”悬浮提示。依赖图按需加载，上限为 512 个材料、2048 条边；受限时切回材料列表
查看完整汇总。移动节点只影响本地视图，不改变配方或执行。没有汇总数量的配方候选仍显示
图标和名称，并标注“无汇总数量”，不会增加材料需求。“全部”（`ALL`）搜索也匹配这些候选节点，
数量筛选则只匹配具有对应汇总数量的材料。

配置名称、说明和枚举选项均支持中英文，包括 Configured Forge 2.2.3；TOML 枚举标识保持不变。

无限来源须明确标记，如 `#appliedenhancements:infinite_storage_cells` 或
InfiniteStorageCellMarker；普通磁盘容量和 Long.MAX_VALUE 数量不代表无限供料。

## 接口

[中文接入](docs/API_INTEGRATION_ZH.md) · [English](docs/API_INTEGRATION.md) ·
[精确计划](docs/EXACT_CRAFTING_API.md) · [共享投料](docs/BATCH_EXECUTION_API.md)

API 指南位于仓库的 `docs/` 目录；独立的 `apiJavadoc` 任务将 HTML 输出至 `build/docs/api/`。

公开包为 `com.appliedenhancements.api` 及其 client 子包。依赖使用 compileOnly，
运行时单独安装匹配版本。实时世界数据在服务器线程访问，规划 Future 异步等待。
复制计划保留精确元数据，CPU 自行验证执行能力，材料持久接收后确认一次所有权。
内部 runtime、Mixin 和载荷类不是稳定接入点。旧 MaxFast 门面保留 1.0.3 Java 兼容。

## 构建

```powershell
.\gradlew.bat clean assemble apiJavadoc --no-configuration-cache
```

发行包为 `build/libs/appliedenhancements-1.1.4-forge.jar`。
CI 分别以标准 AE2 和 UELM 编译，只上传一个通用 JAR 和生成的 API 文档。

## 已知限制

逻辑数量和计划可用 BigInteger，实际 AE 库存、Provider 与很多附属边界仍为 long。
CPU 接受计划不等于获得精确执行能力。动态或不支持的循环保持各自失败/回退边界。
AELIS 缓存属于本次规划会话，超大合法数量仍会消耗时间与内存。

项目采用 [MIT 许可证](LICENSE)。
