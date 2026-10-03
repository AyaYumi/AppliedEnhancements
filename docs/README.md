# Applied Enhancements API / 接口文档

Current: **1.1.1**, Minecraft **1.21.1**, Java **21**, NeoForge **21.1.220+**,
AE2 **19.2.17+**. Network protocol **9** requires matching client/server builds.

| Contract | Documentation |
| --- | --- |
| Planner, cycle CPU, infinite markers, terminal/menu integrations | [English](API_INTEGRATION.md) · [中文](API_INTEGRATION_ZH.md) |
| Exact BigInteger requests, plan metadata, progress and persistence | [Exact crafting](EXACT_CRAFTING_API.md) |
| One protected inventory view, actual batch count, ownership and rollback | [Shared batch execution](BATCH_EXECUTION_API.md) |
| Native enabled-state queries and exact external task reconciliation | [Native smart doubling / 原生智能倍增](SMART_DOUBLING.md) |
| All configuration paths/defaults and migration | [Configuration](CONFIGURATION.md) |
| Build, JavaDoc, regression and release checks | [Development](DEVELOPMENT.md) |

Public contracts live in `com.appliedenhancements.api` and its `client` subpackage.
Planner implementation, runtime, Mixin, packet and optional reflection bridge
classes are internal. Use compileOnly against the matching separate JAR; do not
shade these packages into an addon. Mutate live game state on its server thread.
Planning futures are asynchronous; never block the server thread on get().

本版新增原生智能倍增状态查询，保留共享批量投料上下文。能力上限不代替真实材料、机器容量或持久所有权。
客户端接口只在客户端加载，可选附属兼容类只在对应模组存在时加载。历史个人机器
验证报告已移出项目，公开文档描述当前源码契约与可重复验证方法。

[Remaining interceptions / 剩余拦截清单](remaining-interceptions.md) — numbered operation checks, fallbacks and TPS limits across both maintained Minecraft versions.
