# Applied Enhancements

<p align="center">
  <img src="artwork/project-logo-ae-text.png" alt="Applied Enhancements" width="720">
</p>

[中文](README_ZH.md) · [API documentation](docs/API_INTEGRATION.md) · [Configuration](CONFIGURATION.md)

**1.1.4-forge** extends AE2 crafting, storage access and pattern terminals on Minecraft
1.20.1 / Forge. It adds shared batch extraction/ownership transactions used by
cycle-aware CPUs, while retaining existing planner and terminal APIs.

Recoverable planning and optional display failures use native fallback or
restore the complete original tasks. Batch reconciliation, metadata attachment,
progress observers and scope cleanup retain these recovery paths. AE2 Crafting
Tree (AE2CT) 1.1.1 compatibility fixes attach exact totals before asynchronous tree
construction. Genuine shortages retain AE2's normal missing-material result.

## Features

| Area | Behavior |
| --- | --- |
| Crafting orders | Native, long-range or exact BigInteger input modes; exact quantities with a 1,048,576-character transport limit |
| AELIS | Session-local planner, exact material/byte metadata, cycle solving and safe supported fallback |
| Plan review | Exact material columns, cyclic quantities, filters/search, interactive dependency graphs and optional EMI favorites / JEI missing-material bookmarks |
| Cycle execution | One protected inventory view for initial/batch extraction, phase limits, rollback and pending returns |
| Manual confirmation | Finite stock reservations across open confirmations; explicitly infinite sources remain available |
| Storage | Pattern/container caches, storage-bus candidate index and validated import/export slot hints |
| Infinite cells | Explicit tags/runtime markers/current compatible cells, no inference from a huge finite count |
| Pattern terminals | Duplicate/invalid filters, selection, atomic Cut/Paste and Quick Move integration |
| Item actions | AE network context menu plus optional JEI recipe/search/extract/give integration |
| Logging | No direct console output or INFO/DEBUG/TRACE logs; necessary WARN/ERROR messages are limited per category and globally by severity |

## Requirements and installation

Java 17, Minecraft 1.20.1 and Forge 47.4.10+ (build baseline: 47.4.20) are required.
The universal JAR supports standard AE2 15.4.10 and UELM 15.5.4. Install either
AE2 implementation in the instance; the Applied Enhancements file is the same.
ExtendedAE, AE2WTLib, JEI, EMI, AdvancedAE,
Data Energistics and other integrations remain optional. Compatibility with newer
addon versions depends on their internal hooks.

Build `build/libs/appliedenhancements-1.1.4-forge.jar` and install the same build on
client and server. Internal network protocol is `1.1.4-forge-5`; update both sides together. Addons use the separate public API; this
mod is not embedded in their JARs.

## Configuration and use

The active instance generates `config/appliedenhancements-common.toml`.
Order interception, progress display, enhanced material preview and automatic
AELIS default off; cache/storage optimizations default on. See the complete
[18-option reference](CONFIGURATION.md) before enabling a planner mode.

Pattern Quick Move defaults to right-click; item actions default to Alt +
right-click. Both are GUI-only, independently configurable Forge key bindings.
Server movement/extraction revalidates permissions, source items and destination
capacity. JEI give actions retain JEI's synchronized cheat permissions.

The custom confirmation screen opens after quantity selection, including calculation
and replanning progress. Completed non-AELIS results return to AE2's original view.
Missing materials are highlighted; bookmarking prefers EMI favorites and falls back
to JEI bookmarks. Both mods are optional. When installed, EMI's sidebars and search
also remain available on the slotless AELIS confirmation screen. Table headers and amounts share centered
columns; material names remain left-aligned. Hold Shift when Start is disabled to submit a waiting-input order;
an available CPU with enough crafting storage is still required, and each missing
material amount must fit in a signed `long` (at most `Long.MAX_VALUE`). This does
not create missing ingredients or bypass server submission checks.
The screen defaults on; set `crafting.aelis.enable_plan_screen = false` in the
client's common configuration to keep AE2's original confirmation view without
changing planning or execution. Its UI translation key is `enable_aelis_plan_screen`.

Switch between Materials and Craft Tree to inspect the same plan. Drag nodes to move
them, drag the background to pan, scroll or use zoom buttons, and click cyclic-group
headers to fold or expand. Filters/search retain related branches. Node text wraps;
compact layouts place instructions in Graph Help. The on-demand graph is bounded
to 512 materials and 2048 edges; use Materials for the full summary if truncated.
Moving nodes affects only the local view, not recipes or execution. Recipe alternatives
without summary quantities show their icon and name with “No summary amount”; they do
not add material demand. The All (`ALL`) filter searches these candidate nodes too,
while quantity filters match only materials with the corresponding summary amounts.

Configuration names, tooltips and enum labels support English and Simplified Chinese,
including Configured Forge 2.2.3. TOML enum identifiers remain unchanged.

Infinite supply requires an explicit marker such as the
`#appliedenhancements:infinite_storage_cells` item tag or InfiniteStorageCellMarker.
Finite long counts and large disks are not themselves infinite supply.

## Developer API

[English integration](docs/API_INTEGRATION.md) · [中文接入](docs/API_INTEGRATION_ZH.md)
· [Exact plans](docs/EXACT_CRAFTING_API.md) · [Batch transactions](docs/BATCH_EXECUTION_API.md)

API guides are maintained in the repository's `docs/` directory. The separate
`apiJavadoc` task generates HTML under `build/docs/api/`.

Public packages are `com.appliedenhancements.api` and `api.client`. Use compileOnly
with a matching separate JAR. Acquire live state on the server thread; wait for
planner futures asynchronously. Keep exact metadata when copying plans, determine
your CPU's actual execution capability, and confirm durable ownership once.
Internal runtime/Mixin/packet classes are not addon entry points. The deprecated
MaxFast facade remains for existing 1.0.3 Java integrations.

## Building

```powershell
.\gradlew.bat clean assemble apiJavadoc --no-configuration-cache
```

The runtime distribution is `build/libs/appliedenhancements-1.1.4-forge.jar`.
CI compiles against standard AE2 and UELM, then uploads one universal JAR and
the generated API documentation.

## Known limits

Plans and logical quantities can use BigInteger; AE inventories, provider dispatch
and many optional-mod boundaries remain long. Admission to a CPU does not prove
exact execution. Unsupported dynamic/cyclic branches preserve their documented
failure/fallback boundary. AELIS caches are per planning session, not persistent
global recipe graphs. Valid huge quantities still require time and memory.

Licensed under [MIT](LICENSE).
