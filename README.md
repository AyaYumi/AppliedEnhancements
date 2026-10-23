# Applied Enhancements

<p align="center">
  <img src="artwork/project-logo-ae-text.png" alt="Applied Enhancements" width="720">
</p>

[中文](README_ZH.md) · [API documentation](docs/API_INTEGRATION.md) · [Configuration](CONFIGURATION.md)

Applied Enhancements **1.1.4** enhances AE2 crafting, storage access and pattern
terminals for Minecraft 1.21.1 / NeoForge. It provides exact planning, cycle-aware
CPU execution, shared batch extraction/ownership transactions and terminal tools.

## Features

| Area | Behavior |
| --- | --- |
| Crafting orders | Native, large-quantity long or exact BigInteger input modes; exact quantities with a 1,048,576-character transport limit |
| AELIS | Session-local planner, exact material/byte metadata, cycle solving and safe supported fallback |
| Plan review | Compact AELIS screen, exact material columns, cyclic quantities, filters/search, optional JEI bookmarks and a compact recipe dependency tree with draggable nodes |
| Cycle execution | One protected inventory view for initial/batch extraction, phase limits, rollback and pending returns |
| Manual confirmation | Finite stock reservations across open confirmations; explicitly infinite sources remain available |
| Storage | Pattern/container caches, storage-bus candidate index and validated import/export slot hints |
| Infinite cells | Explicit tags/runtime markers/current compatible cells, no inference from a huge finite count |
| Pattern terminals | Duplicate/invalid filters, selection, atomic Cut/Paste and Quick Move integration |
| Item actions | AE network context menu plus optional JEI recipe/search/extract/give integration |
| Logging | No planning traces or startup configuration dumps; repeated faults are limited per category and globally by severity |

## Requirements and installation

Java 21, Minecraft 1.21.1, NeoForge 21.1.220+ and AE2 19.2.17+ are required.
The default build uses AE2 19.2.18. ExtendedAE, AE2WTLib, JEI, AdvancedAE,
Data Energistics and other integrations remain optional. New addon versions need
runtime verification when they change internal hooks.

Build `build/libs/appliedenhancements-1.1.4.jar` and install the same build on
client and server. Internal network protocol is 11, replacing protocol 10 in 1.1.3;
update both sides together. Addons use the separate public API; this mod is not
embedded in their JARs.

## Configuration and use

The active instance generates `config/appliedenhancements-common.toml`.
Order interception, progress display, enhanced material preview and automatic
AELIS default off; cache/storage optimizations default on. See the complete
[18-option reference](CONFIGURATION.md) before enabling a planner mode.

Pattern Quick Move defaults to right-click; item actions default to Alt +
right-click. Both are GUI-only, independently configurable NeoForge key bindings.
Server movement/extraction revalidates permissions, source items and destination
capacity. JEI give actions retain JEI's synchronized cheat permissions.

When enabled and the layout is usable, the AELIS plan screen opens directly after
quantity selection and keeps calculation and replanning in its waiting view.
Completed AE2 or external-planner results use the original confirmation view.
Missing materials are highlighted and can be bookmarked through JEI. Hold Shift
when Start is disabled to submit a waiting-input order; an available CPU with enough
storage is still required. This does not create missing ingredients or bypass
server submission checks. The AELIS plan screen defaults on; set
`crafting.aelis.enable_plan_screen = false` in the client's common configuration to
keep AE2's original confirmation view.

Switch between Materials and Craft Tree to inspect the current plan. Drag nodes
to move them, drag the background to pan, scroll or use the zoom buttons, and click
cyclic-group headers to fold or unfold them. Search and filters highlight matching
materials and retain related branches. Each node fits its own content: long names
wrap, demand and crafting quantities use separate lines, and cyclic quantities add
a row only when present. Instructions wrap below the canvas; compact layouts show
the complete instructions in the Graph Help tooltip.

The graph shows dependencies from scheduled recipes, with dashed links for
alternative inputs. It is limited to 512 material nodes and 2048 edges; switch to
Materials for the full summary if it is truncated or unavailable. Moving nodes
changes the client view only.

Configuration names, tooltips and enum choices are localized in English and
Simplified Chinese, including Configured 2.6.3. TOML comments remain bilingual and
stored enum identifiers remain unchanged.

Infinite supply requires an explicit marker such as the
`#appliedenhancements:infinite_storage_cells` item tag or InfiniteStorageCellMarker.
Finite long counts and large disks are not themselves infinite supply.

## Developer API

[English integration](docs/API_INTEGRATION.md) · [中文接入](docs/API_INTEGRATION_ZH.md)
· [Exact plans](docs/EXACT_CRAFTING_API.md) · [Batch transactions](docs/BATCH_EXECUTION_API.md)

Public packages are `com.appliedenhancements.api` and `api.client`. Use compileOnly
with a matching separate JAR. Acquire live state on the server thread; wait for
planner futures asynchronously. Keep exact metadata when copying plans, determine
your CPU's actual execution capability, and confirm durable ownership once.
Internal runtime/Mixin/packet classes are not addon entry points. The deprecated
MaxFast facade remains for existing 1.0.3 Java integrations.

## Building and validation

```powershell
.\gradlew.bat clean build apiJavadoc --no-configuration-cache
```

The build runs unit regressions for planning, exact quantities, shared transactions,
refunds, reservations, infinite classification, config migration, graph layout and
interaction, bilingual localization and terminal APIs.

## Known limits

Plans and logical quantities can use BigInteger; AE inventories, provider dispatch
and many optional-mod boundaries remain long. Admission to a CPU does not prove
exact execution. Unsupported dynamic/cyclic branches preserve their documented
failure/fallback boundary. AELIS caches are per planning session, not persistent
global recipe graphs. Valid huge quantities still require time and memory.

Licensed under [MIT](LICENSE).
