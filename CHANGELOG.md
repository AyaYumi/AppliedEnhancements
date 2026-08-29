# Applied Enhancements Changelog

## 1.0.3

### Added

- Added an invalid-pattern finder to supported pattern terminals. Results are grouped by machine, highlighted in red, and retain source-machine and original-slot tooltips.
- Added `PatternDuplicateApi.isInvalidPattern(...)` and `findInvalidSlots(...)` for integrations that need the same invalid-pattern classification.

### Changed

- AE2-family Pattern Access Terminals now place Duplicate, Invalid, and Quick Move controls on a dedicated second toolbar row below the title and search field.
- The additional toolbar extends AE2's native header panel seamlessly and keeps ExtendedAE-family terminal layouts unchanged.

### Fixed

- Batch pattern movement now leaves unresolvable encoded patterns in their original slots while atomically moving every valid selected pattern.
- Fixed localized terminal titles being covered by management buttons.
- Synchronized the expanded header with pattern rows, generated slots, the scrollbar, group tooltips, selection bounds, and machine-group action hitboxes.

## 1.0.2

### Added

- Added a configurable item storage-bus slot index that is rebuilt during AE2's existing external-inventory scan.
- Indexed extraction revalidates every candidate and falls back to AE2's original full scan whenever the cached candidates are incomplete.
- Added `performance.storage_bus.enable_slot_index` to `appliedenhancements-common.toml`; it defaults to `true`.
- Added optimized import-bus extraction from the slot it just enumerated, with AE2's full scan retained as fallback.
- Added weak, bounded export-bus target-slot hints shared between simulated and committed insertion.
- Added `performance.io_bus.enable_slot_routing` to `appliedenhancements-common.toml`; it defaults to `true`.
- Batch pattern movement now indexes empty target slots once and uses bit-set reservations instead of rescanning every target inventory for each source pattern.
- Duplicate-pattern results now show a source-machine badge on every pattern and include machine and original-slot details in tooltips.
- Completed English and Simplified Chinese localization coverage for every configuration option, added bilingual generated-TOML comments and mod-list metadata, and added automated localization completeness checks.
- Consolidated the former common and MAX_FAST configuration files into one function-oriented layout, with automatic value-preserving migration and recoverable backups.
- Updated defaults to match the validated modpack profile: enhanced material calculation, progress display, and infinite-cell listing-limit bypass now default to disabled.
- Added `storage.infinite.enable_listing_limit_bypass`, which controls whether marked infinite cells are listed as `Long.MAX_VALUE` and displayed as `9.2E`; it defaults to disabled.
- Changed the default `crafting.max_crafting_order_amount` from one trillion to `Integer.MAX_VALUE` (`2147483647`).

---

# Applied Enhancements 更新日志

## 1.0.3

### 新增

- 在受支持的样板管理终端中新增失效样板查找。结果会按机器分组、使用红色高亮，并保留来源机器与原槽位 Tooltip。
- 新增 `PatternDuplicateApi.isInvalidPattern(...)` 与 `findInvalidSlots(...)`，方便第三方接入使用相同的失效样板判断。

### 调整

- AE2 系列样板管理终端现在会在标题与搜索框下方使用独立的第二行工具栏，依次放置“重复 / 失效 / 移动”。
- 新工具栏会无缝延展 AE2 原生顶部面板；ExtendedAE 系列终端继续保留原有布局。

### 修复

- 批量移动现在会把无法解析的编码样板留在原槽位，同时以事务方式一次性移动全部有效样板。
- 修复管理按钮遮挡不同语言终端标题的问题。
- 让扩展头部与样板行、动态样板槽、滚动条、分组 Tooltip、框选范围及机器组操作命中区保持一致。

## 1.0.2

### 新增

- 新增可配置的物品存储总线槽位索引，并复用 AE2 现有的外部库存扫描完成重建，不额外整箱扫描。
- 索引抽取会重新验证每个候选槽；缓存结果不足时回退 AE2 原版完整扫描。
- 在 `appliedenhancements-common.toml` 中新增 `performance.storage_bus.enable_slot_index`，默认开启。
- 输入总线会优先从刚枚举的槽位抽取，并在结果不足时回退 AE2 原版完整扫描。
- 输出总线新增弱引用、有界的目标槽位提示，在模拟插入与实际插入之间复用。
- 在 `appliedenhancements-common.toml` 中新增 `performance.io_bus.enable_slot_routing`，默认开启。
- 批量移动样板现在只建立一次目标空槽索引，并使用位图记录预留槽位，不再为每个来源样板从头扫描全部目标库存。
- 重复样板结果现在会在每张样板上显示来源机器角标，并在 Tooltip 中标明机器和原槽位。
- 补齐全部配置项的英文与简体中文映射，为生成的 TOML 注释和模组列表元数据加入双语文本，并新增本地化完整性自动检查。
- 将原 common 与 MAX_FAST 两份配置合并为按功能划分的单一配置，并提供保留现有值的自动迁移与可恢复备份。
- 默认配置调整为当前已验证的整合包方案：增强材料统计、进度显示与无限磁盘数量提升现在默认关闭。
- 新增 `storage.infinite.enable_listing_limit_bypass`，控制标记的无限磁盘是否以 `Long.MAX_VALUE` 计入网络并显示为 `9.2E`，默认关闭。
- 将 `crafting.max_crafting_order_amount` 的默认值从 1 万亿调整为 `Integer.MAX_VALUE`（`2147483647`）。
