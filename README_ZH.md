# Applied Enhancements

[English README](README.md)

Applied Enhancements 是一个面向 Applied Energistics 2（AE2）的 NeoForge 功能增强模组。

项目不注册新的方块或物品，主要通过 Mixin 和网络同步扩展 AE2 的自动合成流程：支持 `long` 范围的合成数量、显示合成计算进度、修正超大数量下的材料统计，并提供可选的 AELIS 合成规划器。

> 当前版本：`1.0.6`
>
> 目标平台：Minecraft `1.21.1` / NeoForge / Java `21`

## 1.0.6 更新

| 项目 | 更新说明 |
|---|---|
| AELIS API 来源标识 | 通过 API 成功生成的普通和循环计划均保留 `AELIS` 标识，关闭自动规划时也适用 |
| 目标物循环种子 | 锻造模板复制等循环可从目标物旧库存中借用求解确认需要的启动量；借用种子在新增订单之外归还，失败尝试恢复库存及提取记账 |
| NeoEcoAE 兼容 | 修复 CPU 列表将无限并行错误显示为 `2G` 的问题，受支持的无限 CPU 最终显示为 `9.2E`；有限数量和实际 CPU 能力不变 |
| 接入兼容性 | 相比 `1.0.5`，公共 Java API 签名及内部载荷协议 `3` 均保持不变 |

## 功能概览

| 功能 | 当前行为 |
|---|---|
| Long 范围合成 | 合成订单、网络载荷和规划过程使用 `long` 数量；默认单次上限为 `Integer.MAX_VALUE`（2147483647），可配置到 `Long.MAX_VALUE` |
| 精确数量校验 | 客户端与服务端共同校验输入，拒绝溢出、非法小数、负数以及会导致 AE2 原生规划器计数溢出的请求 |
| 计算进度显示 | 合成确认界面显示等待、准备、编译、执行、原生计算、计划整理、完成或失败等阶段 |
| 规划路径标识 | 计算结果可区分 `AELIS`、`AE2 回退`、`AE2` 和通用的`外部规划器`路径 |
| 重复产物样板筛选 | 在指定样板管理终端提供 AE2 风格按钮，按主产物筛选重复样板，并支持现有搜索框二次搜索 |
| 失效样板查找 | 在指定样板管理终端单独筛出无法再解析的编码样板，并显示来源机器与原槽位 |
| 快速移动样板 | 在样板管理终端中框选、剪切并事务粘贴样板，机器标题提供整组一键剪切/粘贴 |
| 物品右键菜单 | 空手 Alt＋右击 ME 网络物品可取出、合成和复制 ID；Alt＋右击 JEI 物品可查看配方、复制名称/ID，并在作弊模式下获取物品 |
| AELIS 规划器 | 默认不自动介入；第三方 Mod 可通过公共 API 主动调用，或由管理员通过配置显式启用 |
| 循环材料贡献 | AELIS 结果会在每个材料格及其提示中显示该材料由循环样板产出的数量 |
| 手动计划库存锁 | 仅在自动 AELIS 规划器开启时，预留合成确认界面计划使用的 ME 库存，避免提交前被其他任务抢占 |
| 存储总线槽位索引 | 在 AE2 原有外部库存轮询中建立物品到候选槽位的倒排索引；抽取时验证候选槽，结果不足则回退原版完整扫描 |
| 输入输出总线槽位路由 | 输入总线优先从刚枚举的槽位抽取，输出总线复用模拟插入发现的目标槽位；结果不足时均回退原版扫描 |
| 样板缓存 | 缓存 AE2 样板输入有效性和容器物品结果，使用有界实例缓存控制内存占用 |
| 材料汇总修正 | 使用饱和算术处理超大合成计划，避免存储、合成和缺失数量在预览中溢出 |
| 无限容量显示 | 仅对 AE2 创造存储元件、ExtendedAE 无限元件或显式标记的磁盘使用 `9.2E`，不再探测任意存储实现 |
| AE2WTLib 兼容 | 通过可选 Mixin 修正无线合成终端对负数可用量的处理；未安装 AE2WTLib 时不会加载目标类 |
| Provider 批次接口 | 为第三方合成 Provider 提供一次 AE2 原生 CPU 调度周期内成对的批次开始/结束回调 |

## 版本与依赖

| 组件 | 当前要求 | 类型 |
|---|---:|---|
| Minecraft | `1.21.1` | 必需，精确版本 |
| Java | `21` | 开发与运行目标 |
| NeoForge | `21.1.220` 及以上 | 必需 |
| Applied Energistics 2 | `19.2.17` 及以上 | 必需 |
| ExtendedAE | `1.21-2.2.32-neoforge` 及以上 | 可选；提供无限元件识别 |
| AE2WTLib | `19.5.1` 及以上 | 可选兼容依赖；按测试整合包版本声明 |
| Just Enough Items | `19.27.0` 及以上 | 可选；提供 JEI 侧栏与书签栏物品右键菜单 |

上表对应声明的依赖范围。当前构建使用 NeoForge `21.1.220` 与 AE2 `19.2.17` 编译和验证；由于核心功能使用 AE2 内部类和 Mixin 注入点，升级到新的 AE2 大版本前仍应重新检查客户端、服务端和实际合成流程。

量子 CPU、智能倍增和订单包裹接入分别使用可选的 AdvancedAE、Useless Mod/OmniSequence、Data Energistics。发行验证使用了 AdvancedAE `1.6.12` 与 Data Energistics `3.2.0`；这些是验证版本，不代表承诺兼容所有后续版本。

## 安装

目前仓库提供源码构建流程。构建完成后，将以下文件放入客户端和服务端的 `mods` 目录：

```text
build/libs/appliedenhancements-1.0.6.jar
```

同时需要安装匹配版本的 NeoForge 与 AE2。ExtendedAE、AE2WTLib 和 JEI 仅在使用对应兼容功能时安装。

本模组包含服务端配置同步和客户端界面 Mixin，联机环境应在客户端与服务端安装同一发行构建。

## Long 范围合成

数量输入框最多接受 20 个字符，并采用精确整数解析。服务器会再次校验功能开关与订单上限，因此客户端配置无法绕过服务器限制。

默认最大订单量：

```text
2,147,483,647
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

## AELIS 规划器

AELIS（Applied Enhancements Lattice Integer Solver）会在单次合成计算会话中分析配方树，并尝试把可证明安全的节点聚合执行。存在容器物品、复杂候选、可复用输入或其他兼容边界时，规划器会保留局部原生语义，或把整次尝试交回 AE2。

当 AELIS 实际采用循环 SCC 或数量反馈求解时，合成确认界面的对应材料格会增加“循环合成数量”。该数值按循环样板的实际执行次数乘以样板产量统计，是普通“合成数量”的子集，只随当前确认菜单同步。

循环计划还会携带压缩后的已证明执行顺序。AE2 原生合成 CPU 和 AdvancedAE 量子 CPU 优先执行循环及其必要的普通前置，等循环任务派发完且产物返回后再放行其他普通任务。返回的循环产物会继续投入后续循环；即使它同时是订单最终产物，也会按剩余循环输入需求暂存，避免只留下初始种子。默认 `PRESERVE_MINIMUM` 策略会额外规划足够的产量，在完整交付下单数量后留下已证明的最小启动种子向量；订单完成时 AE2 会把这部分余料送回 ME 库存。`MAX_THROUGHPUT` 则关闭跨订单保种。执行进度和待返回产物随 CPU NBT 保存。独立 CPU 必须实现 `AelisCycleAwareCpu` 及完整执行协议，不能只实现标记；不支持的 CPU 会拒绝循环计划。

同时安装 Useless Mod 和 OmniSequence 时，支持智能倍增的提供器可以按当前完整输入库存、当前步骤剩余次数和安全倍率动态扩大循环批量。桥接会提交真实的倍增样板，产物返回后重新计算批量，不增加订单总工作量。动态组件样板、替代输入及带容器返还的样板保留原派发方式。

Data Energistics 的已标记订单包裹支持在原生与量子 CPU 上自动完成，完成计数与实际批量对齐：样板成功派发并登记实际产量后，CPU 按数据能源的“无实体输出”语义结算包裹；循环产物和保留种子未返回时，订单会继续等待。量子 CPU 的完成记录随原订单编号保存，不会生成额外实体包裹，玩家手动取消仍立即生效。普通订单和规划器 API 创建的订单均适用，包括自动 AELIS 关闭的情况。

Useless Mod 的智能倍增只改写循环计划中的普通样板，循环样板在计划构建、API 包装和 CPU 提交时都会还原原始定义与执行次数，循环元数据随计划保留。已保存循环状态的订单也会校正剩余样板，不重置执行进度。读取旧量子 CPU 订单时，如果没有在途产物，会尝试还原被倍增的循环样板；只有重新求解的全部剩余执行次数与旧订单完全一致且不缺料时才恢复，库存和订单链接保持不变。无法证明安全的旧订单会保留原样并记录提示。

具有净正增长且输入精确的循环会被缩点成独立求解的 SCC 区域。因此粉尘、种子与水晶之间的循环可以单独批量计算，同一张大型配方图中的无关原生或混合边界不会再否决该循环。

如果 AE2 的递归过滤器隐藏了闭合该区域所需的候选，内置接入只会通过 AE2 现有合成索引按需查询当前树中实际到达的终端键。它不会遍历全部样板提供者，也不会持久保存整张网络样板副本；恢复的候选只在本次计算会话内存在，并在执行前重新验证。

规划器只保留一套固定的 `AGGRESSIVE` 执行策略，不再提供 `OFF` / `SAFE` / `AGGRESSIVE` 模式选择。自动接入 AE2 原生规划的功能默认关闭；需要自动接入时，必须在服务端配置中显式启用 `crafting.aelis.enable_automatic_planner`。

第三方 Mod 可以通过 `AelisCraftingPlanner` 公共接口主动创建会话并调用 AELIS。API 调用不受 `crafting.aelis.enable_automatic_planner` 开关影响，因此接入方可以自行决定何时使用 AELIS、何时使用自己的规划器或回退 AE2。关闭自动规划不会删除已有订单或 API 订单携带的循环执行元数据。

通过 API 成功生成的普通和循环计划均保留 `AELIS` 来源标识。目标物同时作为循环启动种子时，规划器只从 AE2 忽略的目标物旧库存中开放求解确认缺少的启动量。借用量计入计划的真实输入，并在新增订单之外归还，两种种子策略均适用。普通配方仍忽略目标物旧库存；真实种子或其他材料不足时仍不可提交。分支被拒绝或整次尝试失败时，会回滚借用库存及提取记账。

AELIS 受节点数和编译时间预算约束。编译图仅在当前合成计算会话内复用，不会建立跨世界或跨 Grid 的持久全局缓存。

确定性耐久工具可以按剩余耐久容量批量规划，包括 AE2 为模糊输入选中的替代工具、单次配方使用多件同类工具以及多个耐久输入槽。规划器仍会验证每个候选的剩余物变化；随机、上下文相关或混合剩余物行为继续交回兼容路径。

返回后保持不变的催化剂会在事务聚合图中租用一次，普通消耗输入继续沿已编译子图批量规划。多级精华等“每一级都复用同一个催化剂”的递归配方不再对每一级调用大型 AE2 原生子请求；兼容边界中剩余的普通子请求也会先做库存预检并受原生工作量上限保护。

所有未经证明的局部 AE2 原生边界最多直接处理 `8192` 个逻辑物品。超过上限时 AELIS 会撤销本次尝试并交给后续规划器或 AE2，避免特殊容器、耐久和候选分支在本模组内部执行数十万次线性请求。无效或非正的样板产量会直接回退，不再猜测为单产出。

### 手动合成计划库存锁

库存锁严格跟随 `crafting.aelis.enable_automatic_planner`，没有独立开关。自动规划器关闭时不会创建预留，现有确认界面持有的预留也会停止限制提取并在下次菜单更新时释放；第三方通过 `AelisCraftingPlanner` API 主动调用规划器不会隐式开启库存锁。

规划完成并仍停留在 AE2 合成确认界面时，服务端会全量预留 `ICraftingPlan.usedItems()` 中的物品和流体。任意材料无法完整预留时不保留部分结果，而是根据扣除了其他确认菜单预留量的库存重新计算。实际提取和模拟提取都会保护其他计划的份额；订单提交期间只允许当前计划使用自己持有的份额。

成功提交、返回数量界面、重新规划、关闭菜单或提交异常都会释放预留。普通提交失败会保留预留，允许玩家在同一确认界面选择其他 CPU 后重试。预留只存在于内存中，不跨服务器重启保存，也不会锁定 CPU、能源、样板或供应器处理能力。

## 重复产物样板筛选

以下终端会显示一个 AE2 风格的重复筛选按钮；普通 AE2 系列终端将管理按钮集中在标题与搜索栏下方的第二行工具栏中：

- AE2 样板管理终端；
- AE2WTLib 无线样板管理终端；
- ExtendedAE 扩展样板管理终端；
- ExtendedAE 无线扩展样板管理终端。

启用后，只保留主产物在当前终端中出现至少两次的样板，并按主产物自动聚类排序；相同产物的样板会连续排列并显示浅蓝色槽位背景。每张样板右上角会显示来源机器的小图标，悬停样板可查看机器名称、同名机器序号和原机器槽位；悬停产物分组标题还能查看该组的来源机器汇总。重复判断使用完整的 `AEKey`，忽略产出数量；同一产物即使每次产出数量不同，也会被视为重复。搜索框会继续在重复结果内筛选，关闭按钮后恢复终端原有列表。排序只改变客户端显示，点击操作仍映射到原供应器和原槽位。

## 查找失效样板

上述四类样板管理终端还会提供独立的“失效”按钮。普通 AE2 系列终端的第二行工具栏依次排列“重复 / 失效 / 移动”，避免按钮遮挡本地化标题；ExtendedAE 系列保留原有顶部布局。启用失效筛选后，只显示 AE2 与已注册第三方解析器均无法再解析的编码样板，并按机器类型聚组、使用红色槽位底色。每张结果仍会显示来源机器角标，悬停可查看原机器与原槽位；原搜索框可继续按机器名或样板物品名二次过滤。“重复”“失效”和“移动”三种模式互斥。

## 快速移动样板

上述四类样板管理终端提供独立的“移动”按钮：

- 开启后按住左键拖动可框选当前可见样板，每次框选都会替换上一次选择；
- 左键单击样板可切换其选中状态；
- 对已选择样板右键会打开 AE 风格二级菜单并显示“剪切”；
- 对空样板槽右键会打开二级菜单并显示“粘贴”，且不会触发原终端的取出操作；
- 每个机器组标题前提供“剪”和“贴”按钮，用于整组一键剪切和粘贴；
- 关闭快速移动模式或退出当前终端会清除选择与剪切缓存。

粘贴由服务端重新验证来源、目标与容量。无法再解析的编码样板会被跳过并留在原槽位，其余有效样板仍按全有或全无的事务一次性移动；来源已变化、目标无效、空间不足或执行异常都会取消有效样板的移动并恢复库存。

## 网络物品右键菜单

在 AE2 存储终端及其兼容子类中，空手 Alt＋右击一个网络物品槽会打开操作菜单：

- “取出 1 个”沿用 AE2 的光标提取语义；
- “取出一组”把一组物品移动到玩家背包；
- “自定义数量”接受精确整数和 AE2 数学表达式，并把玩家背包能容纳的部分取出；
- 可合成条目提供“发起合成”；
- “复制物品 ID”写入系统剪贴板。
- “复制名称”复制当前本地化显示名称；
- “搜索同模组物品”把 `@modid` 写入当前 ME 终端搜索框；

手持物品或容器时不会接管右键，AE2 原有的存入与容器填充行为保持不变。自定义数量请求在服务端重新校验当前菜单、同步序号、网络连接、能源、实时库存和玩家背包容量；客户端显示数量不能强制服务器多取物品。

安装 JEI `19.27.0` 及以上时，Alt＋右击 JEI 物品侧栏或书签栏条目会显示：

- 查看合成表；
- 查看用途；
- 复制本地化名称；
- 复制注册 ID；
- 搜索同模组物品；
- 当前正在使用 AE2 存储终端时，可搜索当前物品，并按实时网络条目取出 1 个/一组或发起合成；
- JEI 作弊模式开启且 JEI 允许该物品作弊获取时，显示“获取 1 个”和“获取一组”。

作弊获取完全复用 JEI 自己的作弊模式、服务端权限同步和物品给予方式；本模组不会绕过 JEI 或服务器权限。未安装 JEI 时，兼容类不会加载，AE2 网络物品菜单继续独立工作。

菜单触发键可以在“选项 → 控制 → 按键绑定 → Applied Enhancements”中分别修改：

| 按键配置 | 默认 | 对应菜单 |
|---|---|---|
| 打开样板移动菜单 | 鼠标右键 | 快速移动模式中的样板剪切、粘贴 |
| 打开物品操作菜单 | Alt＋鼠标右键 | AE2 网络物品、JEI 物品与书签栏 |

两项都可以改为鼠标键、键盘键及 NeoForge 修饰键组合，只在 GUI 中生效。升级时原共享按键设置保留给样板移动，新的物品操作绑定默认使用 Alt＋右键；修改其中一项不会影响另一项。

### 第三方规划器边界

除已实现的 Useless Mod 智能倍增兼容外，项目不自动协调其他第三方规划器的执行顺序。第三方实现返回非 AE2 原生的 `ICraftingPlan` 时，结果界面会统一标记为`外部规划器`；该标记只用于展示，不代表已经解决多个规划器同时修改 AE2 流程时的执行顺序冲突。

默认配置下 AELIS 不会自动修改规划结果。未接入公共 API 的整合包只有在明确需要本模组接管 AE2 原生规划时，才应将 `crafting.aelis.enable_automatic_planner` 设为 `true`。升级时旧版规划器配置会自动迁移到 `crafting.aelis.*`。

## 配置

首次启动后只生成一个 COMMON 配置文件：`config/appliedenhancements-common.toml`。

| 配置键 | 默认值 | 有效范围 | 说明 |
|---|---:|---:|---|
| `crafting.enable_long_range_crafting` | `true` | 布尔值 | 启用超过 `Integer.MAX_VALUE` 的合成订单 |
| `crafting.max_crafting_order_amount` | `2147483647` | `1` ～ `Long.MAX_VALUE` | 单次 AE2 自动合成订单的最大数量 |
| `crafting.enable_progress_display` | `false` | 布尔值 | 启用合成计算进度和路径显示 |
| `crafting.enable_enhanced_material_calculation` | `false` | 布尔值 | 启用增强的存储、合成和缺失材料统计 |
| `crafting.aelis.enable_automatic_planner` | `false` | 布尔值 | 允许自动接入 AE2 原生规划，并启用手动计划库存锁 |
| `crafting.aelis.max_nodes` | `100000` | `1000` ～ `1000000` | 单次分析允许的最大节点数 |
| `crafting.aelis.compile_budget_ms` | `2000` | `100` ～ `30000` | 单次配方树分析的时间预算，单位为毫秒 |
| `crafting.aelis.enable_diagnostics` | `false` | 布尔值 | 输出详细的编译、执行与回退诊断日志 |
| `crafting.aelis.cycle_solver.max_scc_nodes` | `256` | `4` ～ `1024` | 单个循环强连通分量允许包含的最大材料节点数 |
| `crafting.aelis.cycle_solver.max_search_states` | `1000000` | `1000` ～ `10000000` | 多候选循环惰性分支搜索允许访问的最大状态数 |
| `crafting.aelis.cycle_solver.budget_ms` | `1000` | `10` ～ `5000` | 每次全图或局部循环求解的时间预算 |
| `crafting.aelis.cycle_solver.seed_policy` | `PRESERVE_MINIMUM` | `PRESERVE_MINIMUM` / `MAX_THROUGHPUT` | 每单保留已证明的最小启动种子；或允许循环使用全部可用库存以追求最大吞吐 |
| `performance.pattern_cache.enabled` | `true` | 布尔值 | 启用样板输入与容器返还物缓存 |
| `performance.pattern_cache.max_entries_per_pattern` | `32` | `8` ～ `256` | 每张样板保留的多键缓存最大条目数 |
| `performance.storage_bus.enable_slot_index` | `true` | 布尔值 | 为物品存储总线启用候选槽位索引 |
| `performance.io_bus.enable_slot_routing` | `true` | 布尔值 | 为输入与输出总线启用经过验证的槽位提示 |
| `storage.infinite.enable_listing_limit_bypass` | `false` | 布尔值 | 将无限磁盘网络数量提升到 `Long.MAX_VALUE` 并显示为 `9.2E` |

AELIS 之前的规划器配置会自动迁移到 `crafting.aelis.*`。原 common 文件会以 `.pre-aelis.bak` 后缀备份，旧拆分规划器文件会改名为 `.migrated.bak`，已有自定义值会被保留。

`crafting.aelis.enable_diagnostics` 会产生大量日志，只应在定位回退原因或兼容问题时临时启用。

安装 Configured 时，本模组会修正其保存 Applied Enhancements 嵌套配置时覆盖同组其他选项的问题。进度显示、增强材料计算、自动 AELIS 和诊断日志可以独立切换，未修改的自定义数量、预算与种子策略会被保留。

## 无限存储磁盘标记

无限显示采用显式标记，不再通过模拟提取猜测某个磁盘是否无限。内置识别以下实现：

| 来源 | 内置支持 |
|---|---|
| AE2 | `ae2:creative_storage_cell` 创造存储元件 |
| ExtendedAE | `extendedae:infinity_water_cell`、`extendedae:infinity_cobblestone_cell`，以及使用其 `InfinityCellInventory` 的自定义无限元件 |
| 其他 Mod / 数据包 | 物品标签 `#appliedenhancements:infinite_storage_cells` |
| Java 接入 | 运行时 `StorageCell` 实现 `InfiniteStorageCellMarker` |

启用 `storage.infinite.enable_listing_limit_bypass` 时，被识别的磁盘会在物品内容提示和 ME 网络数量中使用 `Long.MAX_VALUE` 哨兵，并紧凑显示为 `9.2E`；关闭后保留磁盘原实现报告的数量。标记只声明磁盘本身已经提供无限内容，不会把普通有限磁盘变成真正的无限物品来源。

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
build/libs/appliedenhancements-1.0.6.jar
```

## 验证范围

自动化测试目前覆盖以下重点：

- `long` 数量解析、饱和加法/乘法和原生规划器安全边界；
- 服务端配置同步、计算进度生命周期和路径网络 ID；
- AELIS 执行策略、递归保护、候选回退、数量反馈、稀疏容量求解和深度边界；
- 合成 CPU 执行数量、模拟库存差量、外部计划材料汇总和终端任务生命周期；
- 无限存储白名单、物品标签、运行时标记和网络安全汇总；
- 网络物品菜单注册顺序与精确数量提取载荷边界；
- Provider 批次回调的正常与异常退出配对；
- 手动计划库存预留的全有或全无、并发防超卖、提交所有权与幂等释放；
- Mixin 所属包和目标源码的结构性保护。

`1.0.6` 使用 Java `21` 构建成功，仓库全部 `362` 项单元测试通过，失败、错误、跳过均为零。使用 Gradle Wrapper 执行 `cleanTest build --no-configuration-cache` 可重新运行单元测试并构建 JAR。

新增的 4 项种子作用域测试覆盖：只借用已证明需要的种子并计入真实提取、被拒绝分支回滚、内层已接受借用在外层失败后回滚，以及禁止凭空生成库存或跨事务借用。

已有独立接入验证记录包含锻造模板复制的六个场景、游戏界面中的开始与完成，以及旧 API 调用方回归。`1.0.5` 按键拆分实现还通过了独立客户端检查，覆盖默认右键与 Alt＋右键区分、仅 GUI 生效、独立键盘路由和解绑、自定义修饰键、设置保存重载，以及旧共享绑定迁移。这些外部验证不属于仓库默认单元测试任务。

下面保留此前 `1.0.4` 的合成与 API 运行验证记录：

| 范围 | 结果 | 统计含义 |
|---|---:|---|
| 仓库单元测试 | `358` 项通过 | 失败、错误、跳过均为零 |
| 含 Data Energistics 的隔离运行环境 | `186` 项必需 GameTest 通过 | `35` 项 Applied Enhancements 专项场景，加 `151` 项依赖模组测试 |
| 不含 Data Energistics 的隔离运行环境 | `175` 项必需 GameTest 通过 | `24` 项 Applied Enhancements 专项场景，加 `151` 项依赖模组测试；验证可选接入缺失时仍可运行 |

专项场景覆盖原生与量子 CPU 循环、产物回投、保种、真实智能批量、订单包裹虚拟完成、存档状态、公共 API 兼容和快速移动边界。这些运行结果来自维护者使用可选模组依赖的独立验证环境，该环境尚不属于已纳入版本管理的标准测试源集。全新检出的仓库直接运行 `runGameTestServer` 不会复现全部 35 项场景，依赖模组自带的 GameTest 也不能算成本模组自己的测试。因没有用例而退出不代表场景验证成功。

仓库单元测试使用 `test` 任务。客户端界面及完整整合包行为仍需实际运行验证，包括普通/超大/非法数量、进度显示、配置开关和关闭自动规划后的原生流程。

## 开发者接口

完整的依赖配置、生命周期、线程/侧别要求及接入示例见 [API 接入文档](docs/API_INTEGRATION_ZH.md)。稳定兼容范围仅包括 `com.appliedenhancements.api` 与 `com.appliedenhancements.api.client`；Mixin、运行时实现和 `com.github.appliedenhancements` 下的内部桥接不属于公共 API。

公开顶层 API 共 17 个，其中 16 个为现行接口，一个为已弃用兼容入口。

| 接口 | 用途 |
|---|---|
| `MolecularBalancedBatchProvider` | 在一次 AE2 原版合成 CPU 调度内接收成对的批次开始与结束回调；异常退出也会关闭批次 |
| `AelisCraftingPlanner` | 为其他 Mod 提供 AELIS 会话创建、进度回调、执行结果和失败状态自动回滚接口 |
| `MaxFastCraftingPlanner`（已弃用） | 保留 1.0.3 公开签名，内部委托 AELIS，供旧接入继续加载 |
| `AelisCycleExecutionApi` | 准备可提交计划、复制与查询元数据、保护输入、计算实际循环批次并读写完整运行时 NBT |
| `AelisCycleExecutionPlan` | 提供压缩循环步骤、最小种子和受保护材料键 |
| `AelisCycleSeedPolicy` | 选择订单完成后保留最低种子，或使用全部循环库存 |
| `AelisCycleRuntimeController` | 提供顺序推进、种子消费阻断和最终产物暂存计算 |
| `AelisCycleAwareCpu` | 由完整实现循环调度语义的第三方 CPU 声明支持 |
| `InfiniteStorageCellMarker` | 由第三方运行时 `StorageCell` 实现，声明其内容应使用无限哨兵显示 |
| `InfiniteStorageCells` | 提供公共 `ITEM_TAG` 与物品标签查询，供数据包和 KubeJS 标记磁盘 |
| `PatternDuplicateApi` | 解析样板产物、查找重复或失效样板，并注册第三方样板解析器 |
| `PatternOutputResolver` | 让第三方编码样板向重复筛选功能提供有序产物键 |
| `PatternBatchMoveApi` | 发起快速移动请求，并为自定义服务端菜单注册原子移动处理器 |
| `PatternTerminalIntegrationApi` | 注册兼容 AE2 或 ExtendedAE 行布局的第三方样板终端界面 |
| `PatternQuickMoveSession` | 客户端每屏幕的框选、剪切缓存、覆盖层和粘贴请求会话 |
| `NetworkItemContextMenuApi` | 在 ME 终端网络物品右键菜单中注册第三方客户端操作项 |
| `PatternSlotRef` | 使用服务端容器 ID 与容器内槽号稳定标识当前终端中的样板槽 |

公共接口直接引用 AE2 类型，因此开发者依赖至少需要 AE2 `19.2.17`，并应在相同的 AE2 主版本内完成兼容验证。

`AelisCycleExecutionApi` 提供 `preparePlan`、`guardInputs`、`dispatchedCrafts`、`writeRuntime`、`readRuntime`、`getCyclicCraftAmounts`，使独立 CPU 无需调用内部运行时类。必须使用准备方法返回的计划，通过 `AelisCycleRuntimeController.withCyclePhase(...)` 启用完整阶段协议，并在清理前结算返回产物。旧控制器构造器保留并发行为；MaxFast 兼容入口保留旧 Java 链接，不会自动让独立 CPU 接入新协议。

`AelisCraftingPlanner.createConfigured(...)` 使用服务端配置的节点数与编译预算，但不会检查 `crafting.aelis.enable_automatic_planner`。每个 AE2 合成计算应创建一个会话，并在该计算的实际尝试和模拟尝试之间复用；当结果的 `shouldFallback()` 为 `true` 时，API 已恢复缺失物品计数与候选状态，调用方可以安全地继续自己的规划器或 AE2 原生流程。

需要恢复被 AE2 递归过滤隐藏的精确循环候选时，可使用带 `ICraftingService` 参数的重载。未传入合成服务的旧调用保持原行为，不会进行原始候选查询。

```java
var planner = AelisCraftingPlanner.createConfigured(
        AelisCraftingPlanner.NO_PAUSE,
        AelisCraftingPlanner.ProgressListener.NONE);

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

### 样板筛选与快速移动 API

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

兼容现有行模型的终端可以在客户端初始化阶段注册其精确界面类名。本模组随后会自动添加“重复”“失效”“移动”“剪”“贴”、框选和右键菜单。注册为 `AE2_PATTERN_ACCESS` 的界面必须继承 AE2 样板管理终端并保持其行布局；注册为 `EXTENDEDAE_PATTERN_ACCESS` 的界面必须继承 ExtendedAE 扩展样板终端并保持对应布局。

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
| AELIS 只缓存当前计算会话 | 不提供跨 Grid、跨世界或持久化的配方图缓存 |
| 独立运行验证环境 | 默认 GameTest 任务不会复现发行专项场景；自动化检查不能替代完整整合包的客户端与服务端验证 |
| AELIS 采用单一激进策略 | 自动接入默认关闭；启用前应在实际整合包中验证配方兼容性 |
| 超大订单仍受资源限制 | 合法的 `long` 数量不代表一定能在可接受时间和内存内完成 |

## 许可证

本项目采用 [MIT License](LICENSE)。
