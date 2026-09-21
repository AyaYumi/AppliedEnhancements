# 大整数规划与 CPU 执行 API（1.21.1 / 1.0.9 修订版）

此 API 不依赖 DataEnergistics，也不引用 OmniSequence。OmniSequence 是接入示例，不是允许执行的 CPU 白名单。
客户端与服务端须使用本次同一构建（网络协议 9）；接入方须使用包含这些类的新版 1.0.9 JAR 编译和运行。

## 生成完整订单

在服务器线程调用，返回值可异步等待；不要在服务器线程阻塞 `get()`：

```java
Future<ICraftingPlan> pending = AelisExactCraftingService.begin(
        level, requester, outputKey, new BigInteger("99999999999999999999"));
```

生成的计划仍遵循 AE2 的 `ICraftingPlan`。其 long 字段只是兼容投影，**不是完整订单**。
将完成的计划原样交给 `ICraftingService.submitJob`，由被选 CPU 决定是否接受。
没有统一的 CPU 能力预检、强制仅选 Omni、或“先执行 long 上限这么多就算成功”的降单逻辑。
原生/其他 CPU 不会因为安装 API 就自动获得大整数执行能力；其实现需要读取下列接口。

大整数规划由 `crafting.aelis.enable_big_integer_planning` 配置控制，默认开启，同时需开启长数量输入。
`MAX_CRAFTING_ORDER_AMOUNT` 为 Long.MAX_VALUE 时允许精确超 long 订单；设置更小值仍作为明确的订单限额。
网络输入最多 256 位十进制正整数，这是载荷资源预算，不是 CPU 能力检查。
不支持精确求解的配方分支会报规划失败，不回退成截断的原生订单。

## CPU 获取精确计划

```java
BigInteger output = AelisExactCraftingPlanApi.getFinalOutputAmount(plan);
Map<IPatternDetails, BigInteger> tasks = AelisExactCraftingPlanApi.getPatternTimes(plan);
Map<AEKey, BigInteger> stored = AelisExactCraftingPlanApi.getStoredAmounts(plan);
Map<AEKey, BigInteger> infinite = AelisExactCraftingPlanApi.getInfiniteInputAmounts(plan);
BigInteger bytes = AelisExactCraftingPlanApi.getBytes(plan);
```

这些方法对普通 AE2 计划也返回有效的数量（从 long 提升），返回的映射不可修改。
`requiresExactExecution(plan)` 是给 CPU 实现参考的查询，不在统一提交阶段拒绝其他 CPU。
`infinite` 只包含规划时已明确识别为无限来源的材料；**不能将数量等于 Long.MAX_VALUE 的普通材料推断为无限**。

CPU 可把任务拆成受 long 限制的滚动执行窗口。必须遵守：

1. 完整任务次数保存在 BigInteger 中，仅在提供者实际接受批次后扣除；拒绝或模拟不扣。
2. 在途量加 CPU 库存必须留足 long 空间，以背压控制窗口；不要让一次批次或累加溢出。
3. 最终产物另行维护 BigInteger 剩余量。可使用通用 `AelisExactOutputProgress`：

```java
var outputProgress = new AelisExactOutputProgress(output);
// only after a real final-output delivery; never for SIMULATE
long displayWindow = outputProgress.delivered(actualDelivered);
boolean finished = outputProgress.complete();
```

4. 存档写入 `remaining().toString()`，恢复时 `new BigInteger(text)`；不要保存显示窗口代替真实量。
5. 样板需用可持久化的身份保存并在读取时重新绑定；恢复失败应显式取消/报错，不能丢失账本继续小订单。
6. 无限供应的虚拟输入不能在取消、完成或回滚时作为真实库存退回。Omni 的计划内无限输入采用预留的虚拟供应视图。
7. `AelisExactCraftingCpu.aelis$getRemainingOutput()` 可选用于暴露运行中的精确余量；它不是准入标志。
8. `AelisExactCraftingCpu.aelis$getPendingOutputs()` 返回逐物品的待下发产量：每个未完成样板的精确剩余次数乘单次产量，再按物品合并。不得返回 long 执行窗口，也不得加上已经下发的在途产物。AE2 CPU 状态页自动将这个映射随同一份状态包传给客户端，支持省略物品 key 的增量更新；默认空映射保留原生显示。

## 规划阶段的样板倍增

精确样板次数是完整账本，不是仅包含溢出项的补丁；非空时不得再合并旧的 long 任务表，否则会把原始样板和倍增样板重复计算。
数量型包装样板可实现 `AelisScaledPattern`，返回原样板及相对倍数。无用之物的智能倍增样板已通过可选 Mixin 接入，无须把该模组设为前置。
附加或复制元数据时，会将完整次数按实际包装倍率重新分成整批和原样板余数，并同步重建 long 窗口。
提交前再次改写计划也保留元数据。已对齐计划的复制不会再次乘除倍率；存档保留批次样板定义和精确剩余次数。
该修复不扩展接收机器的单批容量，单次材料和输出仍须符合原接口的 long 范围。

## 其他规划器写入元数据

```java
plan = AelisExactCraftingPlanApi.attachExecutionMetadata(plan, output, tasks, infinite);
```

必须保留返回值（可能是包装计划）。投影的样板集合和精确集合应一致，精确次数是替换值，不是增量。
包装或复制计划时使用 `AelisCycleExecutionApi.copyMetadata(source, target)`，可同时保留循环、材料、字节数和最终订单数量。

## 本次验证

- 单元测试覆盖 19/20 位输入、256 位载荷、跨 long 的交付账本、取消范围恢复。
- 开发服务器验证真实 Mixin 下的大整数材料规划。
- Omni 开发服务器验证真实 CPU 的任务提交计数、SIMULATE 不扣量、实际交付跨 long、NBT 保存恢复、最后一个产物才完成。
- 开发验证用的是受控配方和合成的提供者接受回调，不等同于运行整合包中所有机器配方。
- 万象样板回归：412 项单元测试通过；使用无用之物 2.3.6.3 的真实石头万象样板、智能倍增和提交前二次改写，验证 19 位、20 位及 51 位订单的整批/余数守恒、拒收不扣量、成功接收扣量、取消与存档恢复。NeoForge 21.1.248 + DataEnergistics 3.2.2 + NeoEcoAE 21.2.0-beta3 + LDLib 2.2.39.a 组合验证通过。提供者接受回调仍为受控测试，不声称外部机器能一次收下超 long 材料。

执行验证源码仅在 `-PplanningSmoke` / `-PexactSmoke` 开发运行中加入，不进入普通发布 JAR。
