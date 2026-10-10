# Shared batch execution / 共享批量投料 API

Applied Enhancements **1.1.1-forge**, Java 17, Minecraft 1.20.1, Forge 47.4.20+,
AE2 15.4.10+. [API index](README.md) · [Cycle and integration guide](API_INTEGRATION.md)

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

The pattern key and source inventory must be non-null. A null explicit runtime
selects ordinary inventory access and supplies no cycle/seed protection; use the
controller belonging to the submitted cycle plan. Actual input arrays and every
holder must be non-null. Zero-input patterns may use an empty array, but their
accepted craft count must be determined by the CPU and kept within the phase limit.
Keep dispatch opening, acceptance, output callbacks and closing on the scheduling
thread. The API uses thread-local nesting and does not marshal cross-thread calls.

## Contract failures and secondary outputs

`beginDispatch` validates positive craft counts, the current phase limit and the
actual protected input holders. Violations remain execution-contract errors;
recoverable planning fallback does not authorize a malformed live dispatch.
Record every actual returned output, including byproducts, exactly once through
the cycle runtime. Simulation and planned amounts are not returned materials.

An enclosing transaction matches runtime, pattern and input-array identity. A
nested provider hook shares acceptance; closing it does not undo the owner scope.
An exception after durable transfer must preserve that acceptance and cannot
refund materials now owned by the provider. Refunds cover only still-owned finite
physical inputs; explicitly infinite inputs cannot generate refund stock.

## 中文

原生调度钩子从当前作用域取得上下文；独立 CPU 用显式 runtime 重载，无需访问内部
作用域类。首份和额外份数始终使用同一个库存视图。
库存为 null 时不能发配；真实次数受当前循环步骤上限约束，且仍需检查材料与机器容量。
不要在首份抽料之后重新 acquire，否则可能重新获得保护预算。

样板键和源库存必须非 null；显式 runtime 为 null 表示普通库存访问，不提供循环或
种子保护。循环订单应使用对应控制器。输入数组及各容器必须非 null；零输入样板
可用空数组，真实接收次数由 CPU 确定并限制在阶段上限内。事务打开、确认、产物
回调与关闭都在同一调度线程执行，接口不会自动把异线程调用切回所属线程。

材料交付前打开事务，持久接收后立即确认 accepted。拒收关闭事务会恢复循环进度和
待回收账本；已抽出的实物仍由 CPU 持有，必须通过原视图退款。事务不凭空退款实物。
Provider 原生钩子共享已有事务以避免双重推进；回调可同步返回产物，因此登记应先于交付。

这些接口属于本版公开 API，旧 1.0.x JAR 不含新上下文。循环控制器的保存/回收与
精确计划元数据仍按各自文档处理；不要依赖 runtime 或 Mixin 内部字段。

`beginDispatch` 仍检查正数次数、当前阶段上限及真实受保护输入容器；违反这些条件
是执行契约错误。规划回退不允许错误实物发配。所有实际返还产物（包括副产物）
恰好记账一次，模拟和计划产量不算返还材料。持久移交后的异常不能退款已由供应器
持有的材料；明确无限输入也不能转化为真实退款库存。
