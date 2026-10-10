# Applied Enhancements

[English](README.md) · [接口文档](docs/README.md) · [配置参考](CONFIGURATION.md)

当前 **1.1.1-forge** 为 Minecraft 1.20.1 / Forge 提供 AE2 规划、存储访问和样板终端
增强。本版增加共享批量投料事务，让循环 CPU 的首份抽料、额外份数、回滚和原生
Provider 钩子保持同一材料账本。

可恢复的规划校验与可选显示接口错误改为原生回退或恢复完整原始任务。批次换算、
元数据挂接、进度回调、范围清理和 AE2 Crafting Tree 兼容不再因校验异常直接
打断有效订单；真实缺料仍通过 AE2 的正常缺料结果反馈。

## 功能

| 领域 | 当前行为 |
| --- | --- |
| 合成订单 | 原生、long 上限、大整数三种模式；精确数量输入，传输上限为 1,048,576 字符 |
| AELIS 规划 | 单次会话缓存、精确材料/字节元数据、循环求解和受限安全回退 |
| 循环执行 | 共享保护库存视图、当前阶段次数上限、拒收回滚与产物回收 |
| 手动确认 | 多个确认页之间预留有限材料；明确的无限来源不参与有限锁定 |
| 存储性能 | 样板与容器缓存、存储总线候选槽索引、输入输出总线提示 |
| 无限元件 | 标签、运行标记和明确兼容元件；不凭巨大有限数值推断无限 |
| 样板终端 | 重复/失效筛选、框选、原子剪切粘贴、快速移动接入 |
| 物品菜单 | ME 网络操作及可选 JEI 配方、搜索、抽取、作弊权限联动 |
| 日志 | 诊断默认关闭；重复故障与过程记录按全局一分钟窗口限流 |

## 安装

需要 Java 17、Minecraft 1.20.1、Forge 47.4.20+、AE2 15.4.10+。
默认构建使用 AE2 15.4.10，同一份 JAR 也支持 UELM 15.5.4。ExtendedAE、AE2WTLib、JEI、AdvancedAE 和其他
附属兼容均为可选；内部接口变化后的新版仍需实际运行验证。

构建 `build/libs/appliedenhancements-1.1.1-forge.jar` 后放入两端 mods，客户端和
服务端使用相同构建，内部载荷协议为 `1.1.0-forge-2`。附属使用独立安装的公开 API，不嵌入本模组。

## 配置与操作

配置位于正在使用实例的 `config/appliedenhancements-common.toml`。
订单接管、进度显示、增强材料预览和自动 AELIS 默认关闭；缓存与存储优化默认开启。
完整 [17 项配置](CONFIGURATION.md) 描述分组、默认值、范围和迁移。

样板快速移动默认右键，物品操作默认 Alt＋右键，两项独立绑定且只在 GUI 内生效。
服务端重新验证权限、源物品和容量。JEI 给予物品沿用 JEI 的同步作弊权限。

无限来源须明确标记，如 `#appliedenhancements:infinite_storage_cells` 或
InfiniteStorageCellMarker；普通磁盘容量和 Long.MAX_VALUE 数量不代表无限供料。

## 接口

[中文接入](docs/API_INTEGRATION_ZH.md) · [English](docs/API_INTEGRATION.md) ·
[精确计划](docs/EXACT_CRAFTING_API.md) · [共享投料](docs/BATCH_EXECUTION_API.md)

公开包为 `com.appliedenhancements.api` 及其 client 子包。依赖使用 compileOnly，
运行时单独安装匹配版本。实时世界数据在服务器线程访问，规划 Future 异步等待。
复制计划保留精确元数据，CPU 自行验证执行能力，材料持久接收后确认一次所有权。
内部 runtime、Mixin 和载荷类不是稳定接入点。旧 MaxFast 门面保留 1.0.3 Java 兼容。

## 构建与验证

```powershell
.\gradlew.bat clean build apiJavadoc --no-configuration-cache
```

构建包含规划、精确数量、共享事务、退款、库存预留、无限识别、迁移和终端 API
的单元回归。

## 已知限制

逻辑数量和计划可用 BigInteger，实际 AE 库存、Provider 与很多附属边界仍为 long。
CPU 接受计划不等于获得精确执行能力。动态或不支持的循环保持各自失败/回退边界。
AELIS 缓存属于本次规划会话，超大合法数量仍会消耗时间与内存。

[本版变更](CHANGELOG.md) · 项目采用 [MIT 许可证](LICENSE)。
