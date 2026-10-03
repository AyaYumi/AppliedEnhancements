# Native smart doubling / 原生智能倍增

Current: 1.1.0-forge. [API index](README.md).

`AelisSmartDoublingApi.isExternallyManaged(pattern)` reads the EAEP
`eap$allowScaling()` switch and recognizes existing EAEP/Useless scaled wrappers.
An installed addon alone does not enable the bypass. False allows ordinary local
batching; true preserves the external wrapper and bypasses a second local scale.
`isExternallyManagedProvider(provider)` recognizes the Useless smart-provider marker.

Optional wrappers are read through cached class access to `getOriginal()` and their
native multiplier. They do not need or receive `AelisScaledPattern`. Exact task
metadata is still checked: native splits and remainders are retained when their
weighted sum equals the source work. A saturated long preview keeps a BigInteger
ledger, so bypassing a second scale does not truncate the requested output. Unknown
wrappers cannot be inferred from their name or from the presence of a provider mod.

This API performs no network/storage scans. Reflection discovery is cached per
class; enabled state is read again for each decision. New addon contracts require
an adapter or the established native ABI. Keep client/server builds aligned.

## 中文

EAEP 每个样板启用倍增时才绕过；仅安装附属不会绕过全部任务。已有 EAEP 和无用之物
倍率包装也直接保留。Omni 使用同一查询跳过再次包装和运行时批量扩展，关闭倍增的
普通样板继续走本地批量路径。

绕过倍率不等于丢弃数量记录。Applied 仍验证原生拆分和尾数的总合成量，保留大整数
任务账本。可选接口发现按类缓存，开关实时读取，不访问 AE 网络、不新增每 tick 全网扫描。
