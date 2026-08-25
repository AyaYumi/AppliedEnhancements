# Applied Enhancements API Integration Guide

[中文文档](API_INTEGRATION_ZH.md)

This guide is intended for NeoForge mod authors integrating with Applied Enhancements `1.0.1`. It covers dependency declarations, stable APIs, registration lifecycles, client/server boundaries, transactional requirements, and safe planner fallback behavior.

## Compatibility baseline

| Component | Minimum version | Notes |
|---|---:|---|
| Minecraft | `1.21.1` | Exact game version |
| Java | `21` | Compilation and runtime target |
| NeoForge | `21.1.220` | Matches the current release |
| Applied Energistics 2 | `19.2.17` | Public signatures directly reference AE2 types |
| Applied Enhancements | `1.0.1` | Version covered by this guide |

Only the following packages are part of the stable integration surface:

```text
com.appliedenhancements.api
com.appliedenhancements.api.client
```

The following are implementation details and do not carry source or binary compatibility guarantees:

- `com.appliedenhancements.mixin`;
- `com.appliedenhancements.client`;
- `com.appliedenhancements.runtime`;
- `com.appliedenhancements.integration`;
- `com.github.appliedenhancements`;
- bridges, payloads, constants, and other classes outside the public API packages.

## Development dependency

Applied Enhancements does not yet publish a separate Maven API artifact. Place the release JAR in your project's `libs` directory and reference it with `compileOnly`:

```groovy
dependencies {
    // Integrations normally depend on AE2 directly because its types appear
    // in the public Applied Enhancements signatures.
    compileOnly "org.appliedenergistics:appliedenergistics2:19.2.17"

    // Compile against the API without embedding this mod in your own JAR.
    compileOnly files("libs/appliedenhancements-1.0.1.jar")

    // Add this only when the development run needs the integration at runtime.
    runtimeOnly files("libs/appliedenhancements-1.0.1.jar")
}
```

If your integration unconditionally loads Applied Enhancements API classes, declare a required dependency in `neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
type="required"
versionRange="[1.0.1,)"
ordering="AFTER"
side="BOTH"
```

If all API references are isolated behind an optional compatibility layer, declare an optional dependency instead:

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
type="optional"
versionRange="[1.0.1,)"
ordering="AFTER"
side="BOTH"
```

Optional integrations must isolate every API reference in classes that are loaded only when Applied Enhancements is present:

```java
if (ModList.get().isLoaded("appliedenhancements")) {
    AppliedEnhancementsCompat.register();
}
```

A runtime Mod ID check is not sufficient when a main mod class, field, method signature, or static initializer directly references this API. Such references can still cause `NoClassDefFoundError` before the check executes.

## API overview

| API | Side | Recommended lifecycle | Purpose |
|---|---|---|---|
| `MaxFastCraftingPlanner` | Server | Once per AE2 crafting calculation | Invokes the MAX_FAST planner explicitly |
| `InfiniteStorageCellMarker` | Both | Implemented by a runtime storage type | Marks a runtime infinite-storage implementation |
| `InfiniteStorageCells` | Both | Data pack or runtime query | Exposes the public infinite-cell item tag |
| `PatternDuplicateApi` | Both | Register during Common Setup | Resolves outputs and finds duplicate patterns |
| `PatternOutputResolver` | Both | Register during Common Setup | Decodes outputs from third-party encoded patterns |
| `PatternTerminalIntegrationApi` | Client | Register during Client Setup | Adds support to a compatible pattern-terminal screen |
| `PatternBatchMoveApi` | Client and server | Register server handlers during Common Setup | Performs atomic pattern batch movement |
| `PatternQuickMoveSession` | Client only | Create once per open screen | Manages selection, cut buffers, and overlays |
| `NetworkItemContextMenuApi` | Client only | Register during Client Setup | Extends the ME network-item context menu |
| `PatternSlotRef` | Both | Current terminal session only | Identifies a machine container and pattern slot |
| `MolecularBalancedBatchProvider` | Server | Implemented by the provider type | Receives paired CPU scheduling-batch callbacks |

## 1. MAX_FAST planner

### Core rules

- Applied Enhancements' automatic planner integration is disabled by default, but public API calls are independent of `enableAutomaticMaxFastPlanner`.
- Create one `MaxFastCraftingPlanner` instance for each AE2 crafting calculation.
- The same instance may be reused for the real and simulated attempts of that calculation.
- Planner instances are not thread-safe and must not be shared across calculation roots or parallel threads.
- The API operates on AE2 crafting-tree internals, so Applied Enhancements must be loaded and its corresponding Mixins must be active.

### Creating a planner

Use the server-configured node and compilation budgets:

```java
MaxFastCraftingPlanner planner = MaxFastCraftingPlanner.createConfigured(
        MaxFastCraftingPlanner.NO_PAUSE,
        MaxFastCraftingPlanner.ProgressListener.NONE);
```

Use integration-defined budgets and progress callbacks:

```java
MaxFastCraftingPlanner planner = MaxFastCraftingPlanner.create(
        100_000,
        2_000,
        () -> {
            // Optional cooperative pause point. Throw InterruptedException
            // when the calculation has been cancelled.
        },
        new MaxFastCraftingPlanner.ProgressListener() {
            @Override
            public void compilationStarted() {
            }

            @Override
            public void nodeDiscovered() {
            }

            @Override
            public void executionStarted(long totalUnits) {
            }

            @Override
            public void executionStep() {
            }
        });
```

Both `maxNodes` and `compileBudgetMillis` must be positive.

### Execution and fallback

```java
MaxFastCraftingPlanner.Result result = planner.tryExecute(
        root,
        inventory,
        requestedAmount,
        simulation,
        missingItems);

if (result.branchFailure() != null) {
    // This is a terminal AE2 branch failure and should normally propagate.
    throw result.branchFailure();
}

if (result.applied()) {
    // MAX_FAST applied this request to the supplied simulation inventory and
    // missing-item counter.
    return;
}

if (result.shouldFallback()) {
    // The API restored missingItems and AE2 candidate state. It is now safe to
    // continue with another planner or AE2's native request path.
    runNativePlanner();
}
```

`fallbackReason`, `error`, node statistics, and timing fields are diagnostic information. Do not treat a specific fallback-reason string as a stable protocol. A non-null `branchFailure` is not a normal compatibility fallback.

If `tryExecute` throws `InterruptedException`, a runtime exception, or an error, the wrapper restores the attempt state before propagating the failure.

## 2. Infinite storage markers

### Item tag

Item-backed storage cells should prefer the public tag:

```text
#appliedenhancements:infinite_storage_cells
```

Create this resource in the integrating mod:

```text
src/main/resources/data/appliedenhancements/tags/item/infinite_storage_cells.json
```

Example:

```json
{
  "replace": false,
  "values": [
    "examplemod:infinite_item_cell",
    "examplemod:infinite_fluid_cell"
  ]
}
```

Java integrations can reuse the tag key or query a stack:

```java
TagKey<Item> tag = InfiniteStorageCells.ITEM_TAG;
boolean marked = InfiniteStorageCells.isMarked(stack);
```

KubeJS can also add items to the tag:

```javascript
ServerEvents.tags('item', event => {
  event.add('appliedenhancements:infinite_storage_cells', [
    'examplemod:infinite_item_cell'
  ])
})
```

### Runtime marker

Special AE2 `StorageCell` implementations that cannot be associated reliably with an item can also implement the marker interface:

```java
public final class ExampleInfiniteInventory
        implements StorageCell, InfiniteStorageCellMarker {
    // StorageCell implementation omitted.
}
```

The marker tells Applied Enhancements that the implementation already provides infinite contents and should use the infinite quantity sentinel and compact `9.2E` display. It does not turn a finite cell into an actual infinite source.

## 3. Third-party encoded patterns and duplicate outputs

### Registering an output resolver

Register once from Common Setup through `enqueueWork`:

```java
event.enqueueWork(() -> PatternDuplicateApi.registerOutputResolver(
        ResourceLocation.fromNamespaceAndPath("examplemod", "custom_patterns"),
        100,
        (patternStack, level) -> {
            if (!isExamplePattern(patternStack)) {
                return List.of();
            }
            return List.of(resolvePrimaryOutput(patternStack, level));
        }));
```

Resolver behavior:

- Higher priorities execute first. Equal priorities are ordered by registration ID.
- A `ResourceLocation` can be registered only once.
- Return an empty list when the resolver does not handle the item; the API then tries the next resolver.
- The first returned `AEKey` is the primary output used for duplicate grouping.
- Output quantities are deliberately absent, so duplicate comparison ignores produced amount.
- Resolver exceptions are logged and skipped before the API continues with another resolver or AE2's native decoder.

### Using duplicate detection directly

```java
List<PatternDuplicateApi.PatternEntry> entries = collectEntries();

Map<PatternSlotRef, AEKey> outputs =
        PatternDuplicateApi.indexPrimaryOutputs(entries, level);

Set<PatternSlotRef> duplicateSlots =
        PatternDuplicateApi.findDuplicateSlots(outputs);
```

Integrations with their own stable grouping key can use the generic overload:

```java
Set<PatternSlotRef> duplicates =
        PatternDuplicateApi.findDuplicateSlots(customSlotToKeyMap);
```

`PatternSlotRef.containerId()` is the server ID AE2 assigns to the machine container, not a menu slot index. A reference is valid only while the current terminal remains open.

`PatternDuplicateApi.outputMatchesSearch(...)` checks the localized display names of all resolved output keys against a lowercase filter.

## 4. Compatible pattern-terminal registration

Register the exact client screen class name during Client Setup:

```java
event.enqueueWork(() -> PatternTerminalIntegrationApi.register(
        ResourceLocation.fromNamespaceAndPath("examplemod", "pattern_terminal"),
        PatternTerminalIntegrationApi.Family.AE2_PATTERN_ACCESS,
        "examplemod.client.gui.ExamplePatternAccessScreen"));
```

Available layout families:

| `Family` | Requirements |
|---|---|
| `AE2_PATTERN_ACCESS` | The screen must extend the AE2 Pattern Access Terminal and preserve its slot-row and machine-header layout |
| `EXTENDEDAE_PATTERN_ACCESS` | The screen must extend the ExtendedAE terminal and preserve its corresponding layout |

A compatible registered terminal receives duplicate filtering, Quick Move, box selection, the right-click context menu, and machine-group Cut/Paste controls. Duplicate mode and Quick Move mode are mutually exclusive.

Built-in registrations already cover:

- AE2 Pattern Access Terminal;
- AE2WTLib Wireless Pattern Access Terminal;
- ExtendedAE Extended Pattern Access Terminal;
- ExtendedAE Wireless Extended Pattern Access Terminal.

Registration IDs must be unique, and `screenClassName` must be the exact runtime class name rather than a superclass name.

Fully custom row models cannot obtain the feature by registration alone. They should use `PatternDuplicateApi`, `PatternBatchMoveApi`, and `PatternQuickMoveSession` directly in their own UI implementation.

## 5. Atomic pattern batch movement

### Implementing the server menu extension

```java
public final class ExamplePatternMenu extends AbstractContainerMenu
        implements PatternBatchMoveApi.MenuExtension {

    @Override
    public PatternBatchMoveApi.Result movePatterns(
            ServerPlayer player,
            PatternBatchMoveApi.Request request) {
        // 1. Verify that the player can still access the terminal.
        // 2. Re-read and validate every source pattern.
        // 3. Validate target machines, slots, and capacity.
        // 4. Snapshot every source and target before committing.
        // 5. Restore all slots when any operation fails.
        return PatternBatchMoveApi.Result.success(movedCount);
    }
}
```

### Registering a handler for an external menu

Menus that cannot implement `MenuExtension` directly can register a server handler:

```java
event.enqueueWork(() -> PatternBatchMoveApi.registerMenuHandler(
        ResourceLocation.fromNamespaceAndPath("examplemod", "pattern_menu"),
        100,
        new PatternBatchMoveApi.MenuHandler() {
            @Override
            public boolean supports(AbstractContainerMenu menu) {
                return menu instanceof ExamplePatternMenu;
            }

            @Override
            public PatternBatchMoveApi.Result movePatterns(
                    ServerPlayer player,
                    AbstractContainerMenu menu,
                    PatternBatchMoveApi.Request request) {
                return executeAtomicMove(
                        player, (ExamplePatternMenu) menu, request);
            }
        }));
```

Higher-priority handlers are checked first. The first handler whose `supports(menu)` method returns `true` owns the request. Registration IDs must be unique.

### Sending a client request

```java
PatternBatchMoveApi.requestMove(
        menu.containerId,
        selectedSources,
        targetContainerIds,
        preferredTargetSlot);
```

Fields and limits:

| Field | Meaning |
|---|---|
| `sources` | Source `PatternSlotRef` values; maximum `512` |
| `targetContainerIds` | Candidate target machine container IDs; maximum `128` |
| `preferredTargetSlot` | Preferred destination slot; use `-1` for automatic placement |

Client data is untrusted. The server handler must revalidate the menu ID, player access, source contents, target capacity, and same-source targets, then guarantee all-or-nothing behavior.

`PatternBatchMoveApi` catches handler runtime exceptions and returns `APPLY_FAILED`, but a custom handler remains responsible for restoring any inventory state it modified before throwing.

Use the provided `Result.success(...)` and `Result.failure(...)` factories so moved counts and failure reasons remain internally consistent.

## 6. Custom client Quick Move sessions

`PatternQuickMoveSession` is client-only. Create one instance for each open terminal screen:

```java
private final PatternQuickMoveSession quickMove =
        new PatternQuickMoveSession();

@Override
public void onClose() {
    quickMove.clear();
    super.onClose();
}
```

Typical input lifecycle:

1. Call `setEnabled(true)` to enter Quick Move mode.
2. Call `beginSelection(...)` when the left mouse button is pressed.
3. Call `drag(...)` while the pointer moves.
4. Call `finishSelection(...)` when the button is released.
5. Call `renderSelectionBox(...)` and `renderSelectedSlots(...)` during rendering.
6. Call `cutSelectedPattern(...)` or `cutGroup(...)` to populate the cut buffer.
7. Call `paste(...)` to send the server-authoritative move request.
8. Call `setEnabled(false)` or `clear()` when the mode or screen closes.

`PatternQuickMoveSession` never modifies server inventory directly. The screen must maintain the `Map<PatternSlotRef, PatternSlotRef>` that maps displayed slots back to their original source slots after client-side filtering or sorting.

Disabling the session clears selection and the cut buffer. A box selection replaces the previous selection, while individual pattern clicks can toggle one slot.

## 7. Network-item context-menu extensions

Register entry providers during Client Setup:

```java
event.enqueueWork(() -> NetworkItemContextMenuApi.register(
        ResourceLocation.fromNamespaceAndPath("examplemod", "inspect_item"),
        100,
        context -> {
            if (!context.key().getId().getNamespace().equals("examplemod")) {
                return List.of();
            }
            return List.of(new NetworkItemContextMenuApi.Entry(
                    Component.literal("Copy and inspect"),
                    NetworkItemContextMenuApi.Context::copyId));
        }));
```

Providers are evaluated whenever the menu opens and may inspect `key()`, `storedAmount()`, `requestableAmount()`, and `craftable()`. Higher priorities run first, ties are ordered by registration ID, duplicate IDs are rejected, and a failing provider is logged and skipped.

`Context` exposes `extractOne()`, `extractStack()`, `extractAmount(long)`, `requestCraft()`, `copyName()`, `copyId()`, `searchSameMod()`, and `shareToChat()`. `shareToChat()` opens the built-in editable `[name] namespace:path` draft; when sent, Applied Enhancements clients turn the item fragment into a client-only JEI search link. A third-party action with its own server mutation must send a dedicated payload and revalidate the active menu, permissions, resource key, and amount; the client-side menu entry is never authoritative.

## 8. Provider scheduling-batch callbacks

Providers that need to observe an AE2 crafting CPU scheduling batch can implement the interface on the actual provider instance:

```java
public final class ExampleProvider
        implements MolecularBalancedBatchProvider {
    private KeyCounter[] currentBatch;

    @Override
    public void appliedenhancements$beginBalancedBatch(
            KeyCounter[] firstInputs) {
        currentBatch = firstInputs;
        // Create a batch queue or transaction context.
    }

    @Override
    public void appliedenhancements$endBalancedBatch() {
        try {
            flushBatch();
        } finally {
            currentBatch = null;
        }
    }
}
```

Lifecycle guarantees:

- The begin callback runs before the first `pushPattern` for that provider in one AE2 CPU scheduling pass.
- Later pushes to the same provider in the pass belong to the same batch.
- Every successful begin callback is paired with exactly one end callback, including exceptional push or scheduling exits.
- `firstInputs` is a defensive snapshot of the first attempted pattern inputs.
- A begin callback does not guarantee that the provider will accept the first push.

The default `appliedenhancements$beginAdaptiveBatch(...)` implementation delegates to `appliedenhancements$beginBalancedBatch(...)`; existing implementations do not need to override both methods.

Implementations should release batch state in a `finally` block and should not retain the input snapshot after the batch ends unless they make their own copy.

## KubeJS boundary

KubeJS is supported only for adding item IDs to the infinite-storage-cell tag. There is no KubeJS API for:

- MAX_FAST planner sessions;
- encoded-pattern Java resolver registration;
- pattern-terminal screen registration;
- network-item context-menu registration;
- atomic batch movement handlers;
- provider scheduling-batch callbacks.

These features require AE2 Java types, client UI integration, or server-authoritative transactional validation and must be implemented by a Java mod.

## Pre-release integration checklist

- [ ] Imports are limited to the stable API packages.
- [ ] Optional compatibility classes cannot load when Applied Enhancements is absent.
- [ ] Client APIs are referenced only from client classes and Client Setup.
- [ ] Registration IDs use the integrating mod's namespace and are unique.
- [ ] MAX_FAST planner instances are not shared across calculations or threads.
- [ ] Normal planner fallback continues through another planner or AE2's native path.
- [ ] Batch movement revalidates every client-supplied field on the server.
- [ ] Batch movement restores every source and target after failure.
- [ ] `PatternQuickMoveSession` is cleared when its screen closes.
- [ ] Custom network-item menu server actions revalidate every client-supplied field.
- [ ] Infinite markers are applied only to storage implementations that are already infinite.
- [ ] The integration has been tested with AE2 `19.2.17` and the target modpack on both client and server.
