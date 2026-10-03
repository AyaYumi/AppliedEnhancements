# Shared batch execution / 共享批量投料 API

Applied Enhancements **1.1.1**, Java 21, Minecraft 1.21.1, NeoForge 21.1.220+,
AE2 19.2.17+. [API index](README.md) · [Cycle and integration guide](API_INTEGRATION.md)

A CPU must retain one protected inventory view throughout initial extraction,
batch expansion and rollback. Acquiring a fresh view after extracting one recipe
can restore the cycle's budget and permit excess consumption. The public
`AelisBatchExecutionContext` binds that view to one pattern and scheduling attempt.

## Lifecycle

1. Native scheduling hooks acquire from the current AELIS scope. Independent CPUs
   call the explicit `acquire(runtime, pattern, source)` overload with their own runtime.
2. Decline when `inventory()` is null. Clamp the actual craft count to `maximumCrafts()`.
3. Extract the first recipe and additional finite materials from this same view.
4. Open `beginDispatch(actualInputs, actualCrafts)` before giving materials to the provider.
5. Call `accepted()` as soon as the provider reports durable material ownership.
6. Always close the dispatch. Rejection restores cycle progress/pending outputs;
   the CPU still owns and refunds physically extracted materials through the same view.

```java
var context = AelisBatchExecutionContext.acquire(runtime, pattern.getDefinition(), source);
var protectedInventory = context.inventory();
if (protectedInventory == null) return;
long crafts = Math.min(requested, context.maximumCrafts());
// Validate and extract all actual inputs through protectedInventory.
try (var dispatch = context.beginDispatch(actualInputs, crafts)) {
    if (provider.pushPattern(pattern, actualInputs)) {
        dispatch.accepted();
    } else {
        // Return every still-owned extracted input through protectedInventory.
    }
}
```

The example leaves recipe-specific extraction/refunds to the CPU. A failed
provider call after ownership transfer must confirm that transfer before cleanup;
otherwise rollback could reissue an already owned batch. `acceptCurrentDispatch()`
lets API committers confirm an enclosing transaction at their durable commit point.

`beginProviderDispatch(runtime, pattern, inputs)` reuses a matching enclosing
transaction rather than advancing the cycle twice. It matches the runtime, pattern
and the same input-holder array identity. Use the real holders; do not substitute
copies for nested hooks. Closing a nested view does not roll back the outer scope.

`hasCycleProtection()` prevents infinite-inventory shortcuts from bypassing active
seed/cycle guards. `maximumCrafts()` is a phase limit, not a guarantee of stock,
queue capacity, energy, permission or exact BigInteger execution. Contexts are
attempt-local and thread-bound; never cache or move them to a planning worker.
The explicit-runtime overload does not require an internal ThreadLocal scope.

## 中文

原生调度钩子从当前作用域取得上下文；独立 CPU 用显式 runtime 重载，无需访问内部
作用域类。首份和额外份数始终使用同一个库存视图。
库存为 null 时不能发配；真实次数受当前循环步骤上限约束，且仍需检查材料与机器容量。
不要在首份抽料之后重新 acquire，否则可能重新获得保护预算。

材料交付前打开事务，持久接收后立即确认 accepted。拒收关闭事务会恢复循环进度和
待回收账本；已抽出的实物仍由 CPU 持有，必须通过原视图退款。事务不凭空退款实物。
Provider 原生钩子共享已有事务以避免双重推进；回调可同步返回产物，因此登记应先于交付。

这些接口属于本版公开 API，旧 1.0.x JAR 不含新上下文。循环控制器的保存/回收与
精确计划元数据仍按各自文档处理；不要依赖 runtime 或 Mixin 内部字段。
