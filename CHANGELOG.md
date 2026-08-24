# Applied Enhancements Changelog

## 1.0.1

### Added

- Added an extensible context menu for items in AE2 storage terminals.
  - Extract one item, one stack, or an exact custom amount.
  - Start an available ME autocrafting request.
  - Copy the localized name or registry ID.
  - Search the terminal for items from the same mod.
  - Open an editable chat draft containing the item name and ID.
- Added context-menu support for JEI's ingredient list and bookmark list.
  - Show recipes or uses, copy the localized name, and copy the registry ID.
  - Search JEI for items from the same mod.
  - Open an editable item-sharing chat draft.
  - While an AE2 storage terminal is open, search for the current item, extract an exactly matching network item, or start its available autocraft.
  - When JEI cheat mode is enabled, show Give One and Give Stack using JEI's own synchronized permission and give behavior.
- Added `NetworkItemContextMenuApi` so client integrations can contribute additional ME terminal menu entries.
- Added a configurable `Open Item Context Menu` key binding under the Applied Enhancements control category. It defaults to right-click, supports mouse or keyboard bindings in GUIs, and also controls pattern Quick Move cut/paste menus.
- Added server-authoritative exact-amount extraction with menu, serial, connection, power, inventory, and player-capacity validation.

### Changed

- Reduced item height in the network/JEI and pattern Quick Move context menus.
- Made context-menu backgrounds fully opaque and rendered JEI menus at the final screen-render priority.
- Added optional JEI `19.27.0+` integration while keeping startup safe when JEI is absent.

### Fixed

- Fixed item and JEI tooltips rendering through an open context menu.
- Fixed the same tooltip bleed-through risk in the pattern Quick Move cut/paste menu.
- Preserved AE2's original right-click behavior when the player is holding an item or supported container.

---

# Applied Enhancements 更新日志

## 1.0.1

### 新增

- 为 AE2 存储终端中的网络物品加入可扩展右键菜单。
  - 取出 1 个、一组或精确自定义数量。
  - 发起当前可用的 ME 自动合成。
  - 复制本地化名称或注册 ID。
  - 在终端中搜索同模组物品。
  - 打开包含物品名称和 ID 的可编辑聊天草稿。
- 为 JEI 物品列表和书签栏加入右键菜单。
  - 查看合成表或用途、复制本地化名称和复制注册 ID。
  - 在 JEI 中搜索同模组物品。
  - 打开可编辑的物品分享聊天草稿。
  - 打开 AE2 存储终端时，可以搜索当前物品、取出网络中完全匹配的物品，或发起可用的自动合成。
  - JEI 作弊模式开启时，使用 JEI 自己的权限同步和给予逻辑显示“获取 1 个”和“获取一组”。
- 新增 `NetworkItemContextMenuApi`，允许客户端兼容模组向 ME 终端菜单注册额外操作项。
- 在 Applied Enhancements 按键分类中新增可配置的“打开物品右键菜单”绑定；默认右键，可在 GUI 中改为其他鼠标键或键盘键，并同时控制样板快速移动的剪切/粘贴菜单。
- 新增服务端权威的精确数量提取，并校验菜单、同步序号、网络连接、能源、库存和玩家背包容量。

### 调整

- 缩小网络/JEI 与样板快速移动右键菜单的单项高度。
- 菜单背景改为完全不透明，并让 JEI 菜单在屏幕渲染的最后阶段绘制。
- 新增可选 JEI `19.27.0+` 接入；未安装 JEI 时仍可安全启动。

### 修复

- 修复物品和 JEI Tooltip 穿透显示在已打开的右键菜单后方的问题。
- 同时修复样板快速移动剪切/粘贴菜单的 Tooltip 穿透风险。
- 玩家手持物品或受支持容器时，继续保留 AE2 原有右键行为。
