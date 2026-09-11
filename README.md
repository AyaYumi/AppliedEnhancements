# Applied Enhancements

[中文说明](README_ZH.md)

<p align="center">
  <img src="artwork/project-logo-ae-text.png" alt="Applied Enhancements" width="720">
</p>

Applied Enhancements is a Forge quality-of-life and performance addon for Applied Energistics 2 (AE2).

It registers no new blocks or items. Instead, it extends AE2 through Mixins and network synchronization with long-range crafting quantities, crafting-calculation progress, optional high-performance planning, pattern-terminal management tools, explicit infinite-cell integration, and compatibility fixes for popular AE2 addons.

> Current version: `1.0.6-forge`
>
> Target: Minecraft `1.20.1` / Forge / Java `17`

## Forge 1.20.1 port

| Area | Update |
|---|---|
| Platform | Ports the 1.0.6 feature set to Minecraft 1.20.1, Forge and Java 17, with AE2 15.4.10. |
| Networking and persistence | Uses Forge SimpleChannel protocol `1.0.6-forge-1` and AE2 15 NBT serialization. Install the Forge build on both client and server. |
| Client compatibility | Adapts vanilla and ExtendedAE pattern-terminal layouts, SRG Mixin targets and the final AELIS result title when ExtendedAE Plus is present. |
| AELIS and CPU display | Retains planner provenance, requested-output seed accounting, cycle execution and the `9.2E` infinite-CPU display. |
| Distribution | The main JAR embeds MixinExtras; item tags use the 1.20.1 `tags/items` directory. |

This branch is `1.20.1-forge`. Minecraft 1.21.1 support remains on [`1.21.1-neoforge`](https://github.com/AyaYumi/AppliedEnhancements/tree/1.21.1-neoforge). Integrations must recompile against the Forge build and AE2 15; JARs and worlds are not migrated across Minecraft versions by this mod.

## Features

| Feature | Behavior |
|---|---|
| Long-range crafting | Crafting requests, payloads, summaries, and execution support `long` quantities. The default per-order limit is `Integer.MAX_VALUE` and can be configured up to `Long.MAX_VALUE`. |
| Exact validation | Client and server reject overflow, decimals, negative values, and requests that would cross proven native-planner safety boundaries. |
| Calculation progress | The confirmation screen shows queueing, preparation, compilation, execution, native calculation, plan building, completion, and failure states. |
| Planning-path display | Completed results distinguish `AELIS`, `AE2 fallback`, native `AE2`, and generic `external planner` paths. |
| Cyclic material contribution | AELIS results show the per-material amount produced by cyclic patterns inside each confirmation-grid cell and its tooltip. |
| AELIS planner | Provides one AGGRESSIVE planning policy. Automatic integration is disabled by default, while other Java mods may call the public planner API directly. |
| Manual-plan inventory lock | When automatic AELIS is enabled, open confirmation screens reserve the ME items and fluids used by their plans. |
| Storage-bus slot index | Builds an item-to-slot candidate index during AE2's normal external-inventory polling; cached extraction validates every candidate and falls back to AE2's full scan when needed. |
| Import/export bus slot routing | Import buses try the slot they just enumerated, while export buses reuse target slots learned during simulated insertion; incomplete transfers fall back to AE2's scan. |
| Duplicate-output patterns | Supported pattern terminals can show only encoded patterns whose primary output occurs more than once, ignoring output quantity. |
| Invalid-pattern finder | Supported pattern terminals can isolate encoded patterns that can no longer be resolved and show their source machine and slot. |
| Quick pattern movement | Select patterns by clicking or dragging, then atomically cut and paste them between compatible machines. |
| Item context menus | Alt + right-click ME entries to extract, craft, or copy IDs; Alt + right-click JEI entries to view recipes, copy names/IDs, and use JEI-authorized cheat actions. |
| Infinite-cell markers | AE2 creative cells, supported ExtendedAE infinite cells, tagged items, and Java marker implementations use the compact `9.2E` display. |
| Pattern caching | Bounded per-pattern caches reduce repeated input validation and container-item work. |
| Provider batch API | Third-party crafting providers can receive paired scheduling-batch start and end callbacks from the native AE2 CPU. |
| AE2WTLib compatibility | Optional compatibility handles wireless terminal quantity overflow and wireless pattern-terminal controls. |

## Requirements

| Component | Declared range | Build / modpack validation version | Type |
|---|---|---|---|
| Minecraft | `[1.20.1,1.21)` | `1.20.1` | Required; this branch targets 1.20.1 |
| Java | Java 17 bytecode | JDK `17` build; Java `21.0.7` modpack run | Required |
| Forge | `[47.4.10,)` | `47.4.20` | Required |
| Applied Energistics 2 | `[15.4.10,16)` | `15.4.10` | Required |
| GuideME | `[20.1.7,20.2.0)` (required by AE2) | `20.1.7` development runtime; `20.1.15` recorded modpack run | Required AE2 dependency |
| ExtendedAE | `[1.20-1.4.12-forge,)` | `1.20-1.4.19-forge` | Optional; mod ID `expatternprovider` |
| AE2WTLib | `[15.3.3,16)` | `15.3.3-forge` | Optional wireless-terminal integration |
| Just Enough Items | `[15,16)` | `15.49.0.188` | Optional ingredient-list and bookmark menus |

Declared ranges permit loading; they do not establish runtime validation for every included version. This port uses AE2 internal classes and Mixin targets, so other versions and mod combinations need their own checks.

The Forge modpack also loaded AdvancedAE `1.3.6-1.20.1`, ExtendedAE Plus `1.5.5`, NeoEcoAE `20.4.0` and OmniSequence: Transfinite `2.0.0-forge`. Loading these mods does not prove every quantum-CPU, smart-doubling or cyclic-order scenario. Data Energistics virtual-order integration remains in the source but was not runtime-validated for this Forge port.

## Installation

Install the same Applied Enhancements release build on both the client and server, together with compatible Forge, AE2 and GuideME versions. The Gradle development runtime includes GuideME explicitly because the Modrinth Maven dependency for AE2 does not supply transitive dependency metadata.

```text
mods/appliedenhancements-1.0.6-forge.jar
```

ExtendedAE, AE2WTLib, and JEI are optional and only required for their corresponding integrations.

## Long-range crafting

The crafting amount field accepts exact integer input up to 20 characters. The server validates the feature switch and maximum order size again, so client configuration cannot bypass server restrictions.

Default maximum order:

```text
2,147,483,647
```

Maximum configurable value:

```text
9,223,372,036,854,775,807
```

Overflowing, fractional, and negative input is rejected rather than truncated or wrapped. A valid `long` request may still be rejected when recipe multiplication, output aggregation, or an unproven native boundary cannot be processed safely.

## AELIS planner

Useless Mod smart doubling rewrites only ordinary patterns in cyclic plans, preserving cyclic firing counts and execution metadata. Cyclic wrappers are normalized at plan construction, API wrapping, and CPU submission. Saved orders with existing cycle metadata also normalize pending patterns without resetting their progress. When loading a legacy quantum CPU order with no in-flight outputs, recovery unwraps scaled cyclic patterns only if a new solve proves exactly the same remaining work without missing materials. Inventory and crafting links are preserved; unprovable orders remain intact and are logged.

AELIS (Applied Enhancements Lattice Integer Solver) analyzes one AE2 crafting calculation and aggregates recipe nodes whose behavior can be proven safe. Container items, complex alternatives, reusable inputs, random behavior, and other compatibility boundaries retain local native semantics or cause the attempt to roll back and fall back.

When AELIS applies a cyclic or quantity-feedback plan, the confirmation grid shows `Cyclic Craft` for each material produced by those cyclic pattern firings. This value is a subset of the normal crafted amount and is transported only with the active confirmation menu.

Cycle plans also carry a compressed, proven runtime schedule. AE2's native crafting CPU and AdvancedAE quantum CPUs prioritize cycles and their necessary ordinary prerequisites, releasing other ordinary tasks after cyclic dispatch and output returns finish. Returned cycle products feed subsequent cyclic work; even requested final-output material is retained up to remaining cyclic input demand instead of retaining only the initial seed. With the default `PRESERVE_MINIMUM` policy, AELIS plans enough production to deliver the full requested output and leave the proven minimum startup vector behind; AE2 returns that remainder to ME storage when the order finishes. `MAX_THROUGHPUT` disables cross-order preservation. Progress and pending cycle outputs persist in CPU NBT. Independent CPUs must implement `AelisCycleAwareCpu` and the execution protocol, not only the marker; unsupported CPUs reject cycle plans.

With Useless Mod and OmniSequence installed, smart-doubling providers can expand cyclic batches based on complete available inputs, remaining firings in the current step, and the safe multiplier. The bridge submits a real scaled pattern and recalculates batch size after output returns without adding work to the order. Dynamic component patterns, alternative inputs, and container-return patterns retain their original dispatch path.

The source retains Data Energistics no-output completion hooks for native and quantum CPUs, with counters aligned to actual batches and saved order IDs. This optional integration still needs Forge runtime validation; its previous NeoForge results do not establish compatibility here.

Productive exact-input cycles are condensed into independently solved SCC regions. A supported dust/seed/crystal loop can therefore be batched even when unrelated branches of the same crafting calculation retain native or hybrid execution.

If AE2's recursion filter hides a candidate needed to close such a region, the built-in integration performs an on-demand lookup for that reached terminal key through AE2's existing crafting index. It does not scan every provider or persist a network-wide pattern copy; restored candidates are session-local and revalidated before execution.

The planner exposes one fixed `AGGRESSIVE` policy. It no longer has OFF/SAFE/AGGRESSIVE mode selection.

Automatic interception of AE2's native planner is disabled by default:

```toml
[crafting.aelis]
enable_automatic_planner = false
```

Third-party Java mods may invoke `AelisCraftingPlanner` regardless of this switch. The automatic switch only controls Applied Enhancements' built-in interception and the manual-plan inventory lock; disabling it does not remove cycle execution metadata from an existing or API-created order.

Successful API plans retain their `AELIS` source label for both ordinary and cyclic recipes. When the requested output is also a cycle's startup seed, the planner exposes only the missing startup amount proven by the solver from stock hidden by AE2's output-ignore operation. That borrowed amount is included in the plan's real inputs and returned separately from the new order, under either seed policy. Ordinary recipes continue to ignore existing output stock; absent seeds or other required materials still prevent submission. Rejected branches and unsuccessful attempts roll back borrowed stock and extraction accounting.

Integrations that need recursion-hidden exact cycle recovery can use the `createConfigured(..., ICraftingService)` overload. Existing overloads retain tree-only behavior and never query raw network candidates.

Unproven local native boundaries are limited to `8192` logical items. Above that limit, the attempt is rolled back and delegated instead of issuing an unbounded number of linear AE2 requests.

### Manual-plan inventory lock

The inventory lock has no independent switch. It is active only while automatic AELIS integration is enabled.

After a plan completes and remains on the confirmation screen, the server reserves all items and fluids in `ICraftingPlan.usedItems()`. Reservations are all-or-nothing and protect other open plans from consuming the same inventory. They are released after successful submission, cancellation, replanning, menu closure, or submission exceptions.

Reservations exist only in memory and do not survive a server restart. They do not reserve crafting CPUs, energy, patterns, or provider processing capacity.

## Pattern-terminal tools

The following terminals are supported:

- AE2 Pattern Access Terminal;
- AE2WTLib Wireless Pattern Access Terminal;
- ExtendedAE Extended Pattern Access Terminal;
- ExtendedAE Wireless Extended Pattern Access Terminal;
- ExtendedAE Universal Wireless Extended Pattern Access Terminal via AE2WTLib (`GuiUWirelessExPAT`).

AE2-family terminals place Duplicate, Invalid, and Quick Move controls together on a second toolbar row below the title and search field, preventing localized titles from being covered. ExtendedAE-family terminals retain their existing top-row layout.

### Duplicate-output mode

- Shows only patterns whose primary output occurs at least twice.
- Ignores output quantity when comparing primary outputs.
- Groups equal outputs together and highlights them with a light-blue slot background.
- Shows a source-machine badge on every result; hovering a pattern reveals its machine name, same-name machine ordinal, and original machine slot.
- Summarizes all source machines in the output-group header tooltip.
- Keeps the terminal's normal search field available inside the duplicate result set.
- Changes client display order only; interactions still map to the original provider and slot.

### Invalid-pattern mode

- Shows only encoded patterns that AE2 and registered output resolvers can no longer resolve.
- Groups invalid patterns by machine type, highlights them in red, and preserves source-machine and original-slot tooltips.
- Keeps the terminal search field available for filtering by source machine or pattern item name.

### Quick Move mode

- Left-click patterns to toggle selection.
- Hold and drag the left mouse button to replace the selection with a box selection.
- Right-click selected patterns to open an AE-style Cut menu.
- Right-click empty pattern slots to open the Paste menu without extracting a pattern.
- Use per-machine Cut and Paste controls for whole groups.
- Clear selection and cut buffers when the mode or terminal closes.
- Duplicate-output, invalid-pattern, and Quick Move modes are mutually exclusive.

Paste requests are revalidated and committed atomically by the server. Encoded patterns that can no longer be resolved are skipped and remain in their source slots, while every valid selected pattern is moved together. Stale sources, invalid targets, insufficient capacity, or execution failures leave no partial movement.

## Network-item context menu

Alt + right-click a network item with an empty cursor in an AE2 storage screen or compatible subclass to open the action menu. It can extract one item to the cursor, move one stack to the player, extract a custom exact amount into available player-inventory space, start an available autocraft, or copy the resource ID.

Holding an item or container preserves AE2's original right-click storage and container-filling behavior. The menu can also copy the localized name and search the terminal for items from the same mod. Custom extraction is revalidated by the server against the active menu, synchronized entry serial, connection, power, current storage, and player inventory capacity. Third-party client integrations can contribute entries through `NetworkItemContextMenuApi`.

With JEI `15.49.0` or newer installed, Alt + right-clicking an ingredient-list or bookmark entry can show its recipes or uses, copy its localized name or registry ID, or search JEI for items from the same mod. While an AE2 storage terminal is open, an exactly matching synchronized network entry also enables ME extraction, ME autocrafting, and terminal search. If JEI cheat mode is active and JEI can provide a cheat stack for the ingredient, the menu also offers Give One and Give Stack. These actions reuse JEI's own synchronized permission and give behavior; Applied Enhancements does not bypass JEI or server permissions.

Two independent triggers are configurable under Options → Controls → Key Binds → Applied Enhancements:

| Binding | Default | Menus |
|---|---|---|
| Open Pattern Quick Move Menu | Right mouse button | Pattern Cut/Paste in Quick Move mode |
| Open Item Actions Menu | Alt + right mouse button | AE2 network entries, JEI ingredients and bookmarks |

Both support mouse or keyboard rebinding and Forge modifiers, and are active only in GUIs. Existing shared key settings are retained for pattern Quick Move; the new item-action binding starts with Alt + right-click. Changing either binding does not change the other.

## Configuration

One COMMON configuration file is generated on first launch: `config/appliedenhancements-common.toml`.

| Key | Default | Description |
|---|---:|---|
| `crafting.enable_long_range_crafting` | `true` | Enables orders above `Integer.MAX_VALUE` |
| `crafting.max_crafting_order_amount` | `2147483647` | Maximum amount in one AE2 crafting order |
| `crafting.enable_progress_display` | `false` | Enables calculation progress and path display |
| `crafting.enable_enhanced_material_calculation` | `false` | Enables enhanced stored, craftable, and missing material statistics |
| `crafting.aelis.enable_automatic_planner` | `false` | Enables automatic AELIS interception and manual-plan inventory locking |
| `crafting.aelis.max_nodes` | `100000` | Maximum nodes analyzed per attempt |
| `crafting.aelis.compile_budget_ms` | `2000` | Compilation budget per attempt in milliseconds |
| `crafting.aelis.enable_diagnostics` | `false` | Emits detailed planner diagnostics |
| `crafting.aelis.cycle_solver.max_scc_nodes` | `256` | Maximum material nodes in one cyclic strongly connected component |
| `crafting.aelis.cycle_solver.max_search_states` | `1000000` | Maximum lazy branch-search states for multi-candidate cycles |
| `crafting.aelis.cycle_solver.budget_ms` | `1000` | Budget for each global or local cyclic solve |
| `crafting.aelis.cycle_solver.seed_policy` | `PRESERVE_MINIMUM` | Preserves the proven minimum startup seed after each order; `MAX_THROUGHPUT` consumes all usable cycle stock |
| `performance.pattern_cache.enabled` | `true` | Enables pattern-input and container-return caching |
| `performance.pattern_cache.max_entries_per_pattern` | `32` | Maximum multi-key cache entries retained per pattern |
| `performance.storage_bus.enable_slot_index` | `true` | Enables candidate-slot indexing for item storage buses |
| `performance.io_bus.enable_slot_routing` | `true` | Enables validated source/target slot hints for import and export buses |
| `storage.infinite.enable_listing_limit_bypass` | `false` | Raises explicitly marked infinite-cell listings to `Long.MAX_VALUE` and displays them as `9.2E` |

Pre-AELIS planner settings are migrated automatically into `crafting.aelis.*`. The original common file is backed up with a `.pre-aelis.bak` suffix, and a former split planner file is renamed with a `.migrated.bak` suffix. Customized values are preserved.

`crafting.aelis.enable_diagnostics` can produce large logs and should only be enabled temporarily while diagnosing a compatibility problem.

When Configured is installed, a compatibility fix preserves unchanged sibling settings when saving Applied Enhancements nested configuration. Progress display, enhanced material calculation, automatic AELIS, and diagnostics can be toggled independently without resetting custom quantities, budgets, or seed policies.

## Infinite storage-cell integration

Applied Enhancements recognizes these infinite storage sources:

| Source | Integration |
|---|---|
| AE2 | `ae2:creative_storage_cell` |
| ExtendedAE | `expatternprovider:infinity_cell` and custom inventories backed by its `InfinityCellInventory` |
| Data packs / KubeJS | `#appliedenhancements:infinite_storage_cells` item tag |
| Java mods | Runtime `StorageCell` implementation of `InfiniteStorageCellMarker` |

When `storage.infinite.enable_listing_limit_bypass` is enabled, recognized cells are listed as `Long.MAX_VALUE` and displayed as `9.2E`. Disabling it preserves the amounts reported by each cell's original implementation. The marker does not turn a finite storage cell into an infinite source.

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

There are 17 public top-level API types: 16 current types and one deprecated compatibility entry point.

| API | Purpose |
|---|---|
| `AelisCraftingPlanner` | Planner sessions, progress callbacks and safe fallback |
| `MaxFastCraftingPlanner` (deprecated) | Preserves the public `1.0.3` planner signatures while delegating to AELIS |
| `AelisCycleExecutionApi` | Plan preparation, metadata, guarded inputs, actual batch counts and runtime NBT |
| `AelisCycleExecutionPlan` | Proven steps, minimum seeds, protected inputs and phase metadata |
| `AelisCycleSeedPolicy` | Minimum-seed preservation or maximum throughput |
| `AelisCycleRuntimeController` | Cyclic dispatch, returned-output tracking and retention |
| `AelisCycleAwareCpu` | Capability declaration for CPUs implementing the full execution protocol |
| `InfiniteStorageCellMarker` | Runtime infinite-storage inventory marker |
| `InfiniteStorageCells` | Public `ITEM_TAG` and item-tag query |
| `PatternDuplicateApi` | Output resolution and duplicate/invalid-pattern queries |
| `PatternOutputResolver` | Custom encoded-pattern output resolver |
| `PatternTerminalIntegrationApi` | Compatible terminal screen registration |
| `PatternBatchMoveApi` | Client move requests and atomic server handlers |
| `PatternQuickMoveSession` | Per-screen client selection, cut buffer and paste requests |
| `NetworkItemContextMenuApi` | Client ME item-menu entries |
| `PatternSlotRef` | Current terminal's server container and slot identity |
| `MolecularBalancedBatchProvider` | Paired scheduling callbacks on the native AE2 CPU |

`AelisCycleExecutionApi` exposes `preparePlan`, `guardInputs`, `dispatchedCrafts`, `writeRuntime`, `readRuntime`, and `getCyclicCraftAmounts` so CPU integrations do not need internal runtime classes. Use returned prepared plans, opt into the full phase protocol with `AelisCycleRuntimeController.withCyclePhase(...)`, and settle returned output before cleanup. The legacy controller constructors retain concurrent behavior. The deprecated MaxFast facade supports source migration; callers must recompile for Forge/AE2 15 and explicitly adopt the cycle CPU protocol.

See the complete [API Integration Guide](docs/API_INTEGRATION.md) for dependency declarations, lifecycle rules, client/server boundaries, code examples, and validation requirements.

## Building and testing

The project uses Gradle Wrapper `8.14.2` and requires JDK 17.

| Task | Windows | Linux / macOS |
|---|---|---|
| Run tests | `.\gradlew.bat test --no-daemon --console=plain` | `./gradlew test --no-daemon --console=plain` |
| Build JAR | `.\gradlew.bat build --no-daemon --console=plain` | `./gradlew build --no-daemon --console=plain` |
| Development client | `.\gradlew.bat runClient` | `./gradlew runClient` |
| Development server | `.\gradlew.bat runServer` | `./gradlew runServer` |
| GameTest server | `.\gradlew.bat runGameTestServer --no-daemon --console=plain` | `./gradlew runGameTestServer --no-daemon --console=plain` |

Build output (includes MixinExtras; the `-slim.jar` is not the installation artifact):

```text
build/libs/appliedenhancements-1.0.6-forge.jar
```

## Validation

On 2026-09-09, the Forge build passed all `370` repository unit tests with JDK `17`: zero failures, errors or skipped tests. These include regression checks for the Forge wireless-screen class and every row/column of the ExtendedAE selection area at multiple screen origins and row counts. Run `cleanTest build --no-daemon --console=plain` with the Gradle Wrapper to repeat the suite and build the JAR. Validation used an external dependency mirror/cache; the mirror configuration is not part of this repository.

The unit suite covers quantity bounds, planner fallback and cycle/seed accounting, configuration and packet contracts, inventory reservations, pattern movement, and API boundaries. A separate Forge 47.4.20 / AE2 15.4.10 modpack record from 2026-09-08 covers:

| Runtime check | Recorded result |
|---|---|
| Ordinary crafting | Requested 64 planks through an ME terminal; 16 logs consumed and 64 planks produced. |
| AELIS crafting | A 1024-plank order and subsequent 64-plank orders completed with matching inventory changes; client and server reported `AELIS`, and the final title displayed the AELIS result. |
| Pattern management | Duplicate/invalid filters were exercised; 4 patterns moved from an OmniSequence provider to an AE2 provider and back with server-side counts checked. |
| Configured amount limit | `2147483648` was rejected with the existing limit of `2147483647`; execution above that limit was not tested. |

These are recorded modpack checks, not the default GameTest task. On 2026-09-09, a separate server-side runtime probe loaded 21 targets, confirmed the crafting-menu bridge was injected, round-tripped cycle NBT with real item keys, and loaded the network handler successfully. The default `runServer` also reached the normal EULA prompt after GuideME was added. This is startup/integration evidence, not a connected multiplayer session or a large cyclic-order test; Data Energistics virtual orders and long-running performance remain unverified. The earlier NeoForge GameTest totals do not describe this port.

## Known limitations

| Limitation | Impact |
|---|---|
| AE2 internal integration | New AE2 major versions require Mixin and real-flow validation |
| Session-local AELIS cache | No persistent recipe graph across grids, worlds, or restarts |
| Separate runtime integration harness | Release scenario results are not reproduced by the checkout's default GameTest task; automated checks do not replace full client/server modpack verification |
| One aggressive planner policy | Automatic integration remains disabled until explicitly enabled |
| Resource-bound large orders | A valid `long` amount does not guarantee acceptable memory or calculation time |

## License

Applied Enhancements is available under the [MIT License](LICENSE).

Issues and compatibility reports can be submitted through the [GitHub issue tracker](https://github.com/AyaYumi/AppliedEnhancements/issues).
