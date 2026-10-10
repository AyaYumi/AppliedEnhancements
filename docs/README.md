# Applied Enhancements API / 接口文档

Current: **1.1.1**, Minecraft **1.21.1**, Java **21**, NeoForge **21.1.220+**,
AE2 **19.2.17+**. Network protocol **9** requires matching client/server builds.

| Contract | Documentation |
| --- | --- |
| Planner, cycle CPU, infinite markers, terminal/menu integrations | [English](API_INTEGRATION.md) · [中文](API_INTEGRATION_ZH.md) |
| Exact BigInteger requests, plan metadata, progress and persistence | [Exact crafting](EXACT_CRAFTING_API.md) |
| One protected inventory view, actual batch count, ownership and rollback | [Shared batch execution](BATCH_EXECUTION_API.md) |
| Native enabled-state queries and exact external task reconciliation | [Native smart doubling / 原生智能倍增](SMART_DOUBLING_API.md) |

Public contracts live in `com.appliedenhancements.api` and its `client` subpackage.
Planner implementation, runtime, Mixin, packet and optional reflection bridge
classes are internal. Use compileOnly against the matching separate JAR; do not
shade these packages into an addon. Mutate live game state on its server thread.
Planning futures are asynchronous; never block the server thread on get().

配置参考与正式更新记录分别位于项目根目录 `CONFIGURATION.md`、`CHANGELOG.md`，本目录只保留 API 文档与索引。

本版补齐可恢复的规划与元数据回退，保留原生智能倍增状态查询和共享批量投料上下文。能力上限不代替真实材料、机器容量或持久所有权。
客户端接口只在客户端加载，可选附属兼容类只在对应模组存在时加载。公开文档描述本分支当前源码的接口契约。
