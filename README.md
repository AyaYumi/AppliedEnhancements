# Applied Enhancements

<p align="center">
  <img src="artwork/project-logo-ae-text.png" alt="Applied Enhancements" width="720">
</p>

[中文](README_ZH.md) · [API documentation](docs/README.md) · [Configuration](docs/CONFIGURATION.md)

**1.1.0-forge** extends AE2 crafting, storage access and pattern terminals on Minecraft
1.20.1 / Forge. It adds shared batch extraction/ownership transactions used by
cycle-aware CPUs, while retaining existing planner and terminal APIs.

## Features

| Area | Behavior |
| --- | --- |
| Crafting orders | Native, long-range or exact BigInteger input modes; exact quantities with a 1,048,576-character transport limit |
| AELIS | Session-local planner, exact material/byte metadata, cycle solving and safe supported fallback |
| Cycle execution | One protected inventory view for initial/batch extraction, phase limits, rollback and pending returns |
| Manual confirmation | Finite stock reservations across open confirmations; explicitly infinite sources remain available |
| Storage | Pattern/container caches, storage-bus candidate index and validated import/export slot hints |
| Infinite cells | Explicit tags/runtime markers/current compatible cells, no inference from a huge finite count |
| Pattern terminals | Duplicate/invalid filters, selection, atomic Cut/Paste and Quick Move integration |
| Item actions | AE network context menu plus optional JEI recipe/search/extract/give integration |
| Diagnostics | Default off process logs; repeated faults and traces share bounded global one-minute windows |

## Requirements and installation

Java 17, Minecraft 1.20.1, Forge 47.4.20+ and AE2 15.4.10+ are required.
The default build uses AE2 15.4.10 and also supports UELM 15.5.4 with the same JAR.
ExtendedAE, AE2WTLib, JEI, AdvancedAE,
Data Energistics and other integrations remain optional. New addon versions need
runtime verification when they change internal hooks.

Build `build/libs/appliedenhancements-1.1.0-forge.jar` and install the same build on
client and server. Internal network protocol is 1.1.0-forge-2. Addons use the separate public API; this
mod is not embedded in their JARs.

## Configuration and use

The active instance generates `config/appliedenhancements-common.toml`.
Order interception, progress display, enhanced material preview and automatic
AELIS default off; cache/storage optimizations default on. See the complete
[17-option reference](docs/CONFIGURATION.md) before enabling a planner mode.

Pattern Quick Move defaults to right-click; item actions default to Alt +
right-click. Both are GUI-only, independently configurable Forge key bindings.
Server movement/extraction revalidates permissions, source items and destination
capacity. JEI give actions retain JEI's synchronized cheat permissions.

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
refunds, reservations, infinite classification, config migration and terminal APIs.

## Known limits

Plans and logical quantities can use BigInteger; AE inventories, provider dispatch
and many optional-mod boundaries remain long. Admission to a CPU does not prove
exact execution. Unsupported dynamic/cyclic branches preserve their documented
failure/fallback boundary. AELIS caches are per planning session, not persistent
global recipe graphs. Valid huge quantities still require time and memory.

[Changes](docs/CHANGES.md) · Licensed under [MIT](LICENSE).
