# Applied Enhancements

[中文说明](README_ZH.md)

<p align="center">
  <img src="artwork/project-logo-ae-text.png" alt="Applied Enhancements" width="720">
</p>

Applied Enhancements is a NeoForge quality-of-life and performance addon for Applied Energistics 2 (AE2).

It registers no new blocks or items. Instead, it extends AE2 through Mixins and network synchronization with long-range crafting quantities, crafting-calculation progress, optional high-performance planning, pattern-terminal management tools, explicit infinite-cell integration, and compatibility fixes for popular AE2 addons.

> Current version: `1.0.1`
>
> Target: Minecraft `1.21.1` / NeoForge / Java `21`

## Features

| Feature | Behavior |
|---|---|
| Long-range crafting | Crafting requests, payloads, summaries, and execution support `long` quantities. The default per-order limit is one trillion and can be configured up to `Long.MAX_VALUE`. |
| Exact validation | Client and server reject overflow, decimals, negative values, and requests that would cross proven native-planner safety boundaries. |
| Calculation progress | The confirmation screen shows queueing, preparation, compilation, execution, native calculation, plan building, completion, and failure states. |
| Planning-path display | Completed results distinguish `MAX_FAST`, `AE2 fallback`, native `AE2`, and generic `external planner` paths. |
| MAX_FAST planner | Provides one AGGRESSIVE planning policy. Automatic integration is disabled by default, while other Java mods may call the public planner API directly. |
| Manual-plan inventory lock | When automatic MAX_FAST is enabled, open confirmation screens reserve the ME items and fluids used by their plans. |
| Duplicate-output patterns | Supported pattern terminals can show only encoded patterns whose primary output occurs more than once, ignoring output quantity. |
| Quick pattern movement | Select patterns by clicking or dragging, then atomically cut and paste them between compatible machines. |
| Item context menus | Right-click ME entries to extract, craft, or copy IDs; right-click JEI entries to view recipes, copy names/IDs, and use JEI-authorized cheat actions. |
| Infinite-cell markers | AE2 creative cells, supported ExtendedAE infinite cells, tagged items, and Java marker implementations use the compact `9.2E` display. |
| Pattern caching | Bounded per-pattern caches reduce repeated input validation and container-item work. |
| Provider batch API | Third-party crafting providers can receive paired scheduling-batch start and end callbacks. |
| AE2WTLib compatibility | Optional compatibility handles wireless terminal quantity overflow and wireless pattern-terminal controls. |

## Requirements

| Component | Requirement | Type |
|---|---:|---|
| Minecraft | `1.21.1` | Required, exact version |
| Java | `21` | Required |
| NeoForge | `21.1.220` or newer | Required |
| Applied Energistics 2 | `19.2.17` or newer | Required |
| ExtendedAE | `1.21-2.2.32-neoforge` or newer | Optional integration |
| AE2WTLib | `19.5.1` or newer | Optional integration |
| Just Enough Items | `19.27.0` or newer | Optional JEI ingredient-list and bookmark context menus |

The minimum NeoForge, AE2, and ExtendedAE versions are aligned with OmniSequence: Transfinite 2.0.0. This release is compiled and verified against AE2 `19.2.17`. New AE2 major versions still require validation because several features use AE2 internal classes and Mixin injection points.

## Installation

Install the same Applied Enhancements version on both the client and server, together with compatible NeoForge and AE2 versions.

```text
mods/appliedenhancements-1.0.1.jar
```

ExtendedAE, AE2WTLib, and JEI are optional and only required for their corresponding integrations.

## Long-range crafting

The crafting amount field accepts exact integer input up to 20 characters. The server validates the feature switch and maximum order size again, so client configuration cannot bypass server restrictions.

Default maximum order:

```text
1,000,000,000,000
```

Maximum configurable value:

```text
9,223,372,036,854,775,807
```

Overflowing, fractional, and negative input is rejected rather than truncated or wrapped. A valid `long` request may still be rejected when recipe multiplication, output aggregation, or an unproven native boundary cannot be processed safely.

## MAX_FAST planner

MAX_FAST analyzes one AE2 crafting calculation and aggregates recipe nodes whose behavior can be proven safe. Container items, complex alternatives, reusable inputs, random behavior, and other compatibility boundaries retain local native semantics or cause the attempt to roll back and fall back.

The planner exposes one fixed `AGGRESSIVE` policy. It no longer has OFF/SAFE/AGGRESSIVE mode selection.

Automatic interception of AE2's native planner is disabled by default:

```toml
[maxfast]
enableAutomaticMaxFastPlanner = false
```

Third-party Java mods can invoke `MaxFastCraftingPlanner` regardless of this switch. The automatic switch only controls Applied Enhancements' built-in interception and the manual-plan inventory lock.

Unproven local native boundaries are limited to `8192` logical items. Above that limit, the attempt is rolled back and delegated instead of issuing an unbounded number of linear AE2 requests.

### Manual-plan inventory lock

The inventory lock has no independent switch. It is active only while automatic MAX_FAST integration is enabled.

After a plan completes and remains on the confirmation screen, the server reserves all items and fluids in `ICraftingPlan.usedItems()`. Reservations are all-or-nothing and protect other open plans from consuming the same inventory. They are released after successful submission, cancellation, replanning, menu closure, or submission exceptions.

Reservations exist only in memory and do not survive a server restart. They do not reserve crafting CPUs, energy, patterns, or provider processing capacity.

## Pattern-terminal tools

The following terminals are supported:

- AE2 Pattern Access Terminal;
- AE2WTLib Wireless Pattern Access Terminal;
- ExtendedAE Extended Pattern Access Terminal;
- ExtendedAE Wireless Extended Pattern Access Terminal.

### Duplicate-output mode

- Shows only patterns whose primary output occurs at least twice.
- Ignores output quantity when comparing primary outputs.
- Groups equal outputs together and highlights them with a light-blue slot background.
- Keeps the terminal's normal search field available inside the duplicate result set.
- Changes client display order only; interactions still map to the original provider and slot.

### Quick Move mode

- Left-click patterns to toggle selection.
- Hold and drag the left mouse button to replace the selection with a box selection.
- Right-click selected patterns to open an AE-style Cut menu.
- Right-click empty pattern slots to open the Paste menu without extracting a pattern.
- Use per-machine Cut and Paste controls for whole groups.
- Clear selection and cut buffers when the mode or terminal closes.
- Duplicate-output mode and Quick Move mode are mutually exclusive.

Paste requests are revalidated and committed atomically by the server. Invalid sources, invalid targets, insufficient capacity, or execution failures leave no partial movement.

## Network-item context menu

Right-click a network item with an empty cursor in an AE2 storage screen or compatible subclass to open the action menu. It can extract one item to the cursor, move one stack to the player, extract a custom exact amount into available player-inventory space, start an available autocraft, or copy the resource ID.

Holding an item or container preserves AE2's original right-click storage and container-filling behavior. The menu can also copy the localized name, search the terminal for the same mod, and open an editable chat draft containing the name and ID. After a draft in the `[name] namespace:path` format is sent, Applied Enhancements clients render that item text as an underlined link. Clicking it runs a client-only command, opens the player inventory, and searches JEI by exact resource location when that JEI search mode is enabled, otherwise falling back to the resolved localized ingredient name; no hidden search command is sent to the server. If JEI is absent, the client shows a local unavailable message instead. Custom extraction is revalidated by the server against the active menu, synchronized entry serial, connection, power, current storage, and player inventory capacity. Third-party client integrations can contribute entries through `NetworkItemContextMenuApi`.

With JEI `19.27.0` or newer installed, right-clicking an ingredient-list or bookmark entry can show its recipes or uses, copy its localized name or registry ID, search JEI for the same mod, or open a chat draft. While an AE2 storage terminal is open, an exactly matching synchronized network entry also enables ME extraction, ME autocrafting, and terminal search. If JEI cheat mode is active and JEI can provide a cheat stack for the ingredient, the menu also offers Give One and Give Stack. These actions reuse JEI's own synchronized permission and give behavior; Applied Enhancements does not bypass JEI or server permissions.

The trigger is configurable under Options → Controls → Key Binds → Applied Enhancements → Open Item Context Menu. It defaults to the right mouse button, can be rebound to another mouse or keyboard key, and controls JEI, bookmarks, AE2 network entries, and pattern Quick Move cut/paste menus. It is active only in GUIs.

## Configuration

Two COMMON configuration files are generated on first launch.

### `config/appliedenhancements-common.toml`

| Key | Default | Description |
|---|---:|---|
| `crafting.max_crafting_order_amount` | `1000000000000` | Maximum amount in one AE2 crafting order |
| `caching.enable_pattern_caching` | `true` | Enables pattern-input and container-item caching |
| `caching.pattern_cache_size` | `32` | Maximum multi-key cache entries per pattern instance |
| `crafting_plan.enable_enhanced_material_calculation` | `true` | Enables enhanced stored, craftable, and missing material statistics |

### `config/appliedenhancements-maxfast.toml`

| Key | Default | Description |
|---|---:|---|
| `features.enableLongRangeCrafting` | `true` | Enables orders above `Integer.MAX_VALUE` |
| `features.enableProgressDisplay` | `true` | Enables calculation progress and path display |
| `maxfast.enableAutomaticMaxFastPlanner` | `false` | Enables automatic MAX_FAST interception and manual-plan inventory locking |
| `maxfast.maxFastMaxNodes` | `100000` | Maximum nodes analyzed per attempt |
| `maxfast.maxFastCompileBudgetMs` | `2000` | Compilation budget per attempt in milliseconds |
| `debug.maxFastDiagnostics` | `false` | Emits detailed planner diagnostics |

`maxFastDiagnostics` can produce large logs and should only be enabled temporarily while diagnosing a compatibility problem.

## Infinite storage-cell integration

Applied Enhancements recognizes these infinite storage sources:

| Source | Integration |
|---|---|
| AE2 | `ae2:creative_storage_cell` |
| ExtendedAE | Built-in infinite water/cobblestone cells and custom inventories backed by its `InfinityCellInventory` |
| Data packs / KubeJS | `#appliedenhancements:infinite_storage_cells` item tag |
| Java mods | Runtime `StorageCell` implementation of `InfiniteStorageCellMarker` |

The marker changes recognition and display only. It does not turn a finite storage cell into an infinite source.

KubeJS example:

```javascript
ServerEvents.tags('item', event => {
  event.add('appliedenhancements:infinite_storage_cells', [
    'examplemod:infinite_item_cell'
  ])
})
```

## Developer API

Stable API packages:

```text
com.appliedenhancements.api
com.appliedenhancements.api.client
```

The API includes:

- MAX_FAST planner sessions, progress callbacks, and safe fallback state restoration;
- duplicate-pattern output resolvers and grouping helpers;
- compatible pattern-terminal registration;
- atomic pattern movement requests and custom server menu handlers;
- per-screen client Quick Move sessions;
- extensible ME terminal network-item context-menu entries;
- infinite storage-cell tags and runtime markers;
- balanced crafting-provider scheduling callbacks.

See the complete [API Integration Guide](docs/API_INTEGRATION.md) for dependency declarations, lifecycle rules, client/server boundaries, code examples, and validation requirements.

## Building and testing

The project uses Gradle Wrapper `8.14.2` and requires JDK 21.

| Task | Windows | Linux / macOS |
|---|---|---|
| Run tests | `.\gradlew.bat test --no-daemon --console=plain` | `./gradlew test --no-daemon --console=plain` |
| Build JAR | `.\gradlew.bat build --no-daemon --console=plain` | `./gradlew build --no-daemon --console=plain` |
| Development client | `.\gradlew.bat runClient` | `./gradlew runClient` |
| Development server | `.\gradlew.bat runServer` | `./gradlew runServer` |
| GameTest server | `.\gradlew.bat runGameTestServer --no-daemon --console=plain` | `./gradlew runGameTestServer --no-daemon --console=plain` |

Build output:

```text
build/libs/appliedenhancements-1.0.1.jar
```

## Known limitations

| Limitation | Impact |
|---|---|
| AE2 internal integration | New AE2 major versions require Mixin and real-flow validation |
| Session-local MAX_FAST cache | No persistent recipe graph across grids, worlds, or restarts |
| No scenario-based GameTests | Automated tests do not replace real client/server modpack verification |
| One aggressive planner policy | Automatic integration remains disabled until explicitly enabled |
| Resource-bound large orders | A valid `long` amount does not guarantee acceptable memory or calculation time |

## License

Applied Enhancements is available under the [MIT License](LICENSE).

Issues and compatibility reports can be submitted through the [GitHub issue tracker](https://github.com/AyaYumi/AppliedEnhancements/issues).
