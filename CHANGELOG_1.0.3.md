# Applied Enhancements 1.0.3

## Added

- Added an invalid-pattern finder to supported pattern terminals. Results are grouped by machine, highlighted in red, and retain source-machine and original-slot tooltips.
- Added `PatternDuplicateApi.isInvalidPattern(...)` and `findInvalidSlots(...)` for integrations that need the same invalid-pattern classification.

## Changed

- AE2-family Pattern Access Terminals now place Duplicate, Invalid, and Quick Move controls on a dedicated second toolbar row below the title and search field.
- The additional toolbar extends AE2's native header panel seamlessly and keeps ExtendedAE-family terminal layouts unchanged.

## Fixed

- Batch pattern movement now leaves unresolvable encoded patterns in their original slots while atomically moving every valid selected pattern.
- Fixed localized terminal titles being covered by management buttons.
- Synchronized the expanded header with pattern rows, generated slots, the scrollbar, group tooltips, selection bounds, and machine-group action hitboxes.

---

# Applied Enhancements 1.0.3 更新日志

## 新增

- 在受支持的样板管理终端中新增失效样板查找。结果会按机器分组、使用红色高亮，并保留来源机器与原槽位 Tooltip。
- 新增 `PatternDuplicateApi.isInvalidPattern(...)` 与 `findInvalidSlots(...)`，方便第三方接入使用相同的失效样板判断。

## 调整

- AE2 系列样板管理终端现在会在标题与搜索框下方使用独立的第二行工具栏，依次放置“重复 / 失效 / 移动”。
- 新工具栏会无缝延展 AE2 原生顶部面板；ExtendedAE 系列终端继续保留原有布局。

## 修复

- 批量移动现在会把无法解析的编码样板留在原槽位，同时以事务方式一次性移动全部有效样板。
- 修复管理按钮遮挡不同语言终端标题的问题。
- 让扩展头部与样板行、动态样板槽、滚动条、分组 Tooltip、框选范围及机器组操作命中区保持一致。
