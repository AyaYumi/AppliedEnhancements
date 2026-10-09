# Native smart-doubling API / 原生智能倍增 API

Minecraft 1.21.1 / NeoForge.

Current: 1.1.1. [API index](README.md).

| Public query | Result |
| --- | --- |
| `isExternallyManaged(IPatternDetails pattern)` | Native doubling is enabled or this is an existing supported external batch wrapper; null returns false |
| `isExternallyManagedProvider(ICraftingProvider provider)` | Provider advertises the optional Useless smart-provider contract; null returns false |

```java
if (AelisSmartDoublingApi.isExternallyManaged(pattern)) {
    // Keep the native wrapper and task count; bypass only your extra local scale.
    dispatchUnchanged(pattern, inputs);
} else {
    dispatchWithLocalBatching(pattern, inputs);
}
```

These are read-only classifications, not capacity, ownership or execution-capability
checks. Query current pattern state on its owning scheduling thread. Compile and
run with 1.1.1 when linking these new types; they are absent from 1.1.0.

`AelisSmartDoublingApi.isExternallyManaged(pattern)` reads the EAEP
`eap$allowScaling()` switch and recognizes existing EAEP/Useless scaled wrappers.
An installed addon alone does not enable the bypass. False allows ordinary local
batching; true preserves the external wrapper and bypasses a second local scale.
`isExternallyManagedProvider(provider)` recognizes the Useless smart-provider marker.

Optional wrappers are read through cached class access to `getOriginal()` and their
native multiplier. They do not need or receive `AelisScaledPattern`. Exact task
metadata retains native splits when their weighted sum matches the source work.
A mismatched finite projection is repaired with an exact remainder; unreadable or
unrecognized rewrites restore original exact tasks. A saturated long preview keeps a BigInteger
ledger, so bypassing a second scale does not truncate the requested output. Unknown
wrappers cannot be inferred from their name or from the presence of a provider mod.

This API performs no network/storage scans. Reflection discovery is cached per
class; enabled state is read again for each decision. New addon contracts require
an adapter or the established native ABI. Keep client/server builds aligned.

## 中文

EAEP 每个样板启用倍增时才绕过；仅安装附属不会绕过全部任务。已有 EAEP 和无用之物
倍率包装也直接保留。接入方使用查询结果避免重复包装和批量扩展，关闭倍增的普通样板继续走本地批量路径。

绕过倍率不等于丢弃数量记录。Applied 保留大整数任务账本；有限投影不一致时补足尾数，
无法识别或读取倍率改写时恢复原始任务继续，可选接口发现按类缓存，开关实时读取，不访问 AE 网络、不新增每 tick 全网扫描。
