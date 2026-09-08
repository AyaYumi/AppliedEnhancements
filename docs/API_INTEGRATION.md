# Applied Enhancements API Integration Guide

[中文文档](API_INTEGRATION_ZH.md)

This guide is intended for Forge mod authors integrating with Applied Enhancements `1.0.6-forge`. It covers dependency declarations, stable APIs, registration lifecycles, client/server boundaries, transactional requirements, and safe planner fallback behavior.

## Compatibility baseline

| Component | Version / validation baseline | Notes |
|---|---:|---|
| Minecraft | `1.20.1` | Declared game range: `[1.20.1,1.21)` |
| Java | `17` | Compilation and runtime target |
| Forge | `47.4.20` | Build/runtime validation version; currently declared range: `[47.4.10,)` |
| Applied Energistics 2 | `15.4.10` | Declared range: `[15.4.10,16)`; public signatures directly reference AE2 types |
| Applied Enhancements | `1.0.6-forge` | Version covered by this guide |

The Forge build preserves the public planner/provider APIs while adapting AE2 dependencies to 15.4.10. The network uses Forge SimpleChannel with protocol `1.0.6-forge-1`; client and server must use this Forge build. Minecraft 1.21.1 jars are not binary compatible with this port.

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
repositories {
    maven { url = "https://api.modrinth.com/maven" }
    flatDir { dirs "libs" }
}
dependencies {
    // Integrations normally depend on AE2 directly because its types appear
    // in the public Applied Enhancements signatures.
    implementation fg.deobf("maven.modrinth:ae2:15.4.10")

    // Compile against the API without embedding this mod in your own JAR.
    compileOnly fg.deobf("com.appliedenhancements:appliedenhancements:1.0.6-forge")

    // Add this only when the development run needs the integration at runtime.
    runtimeOnly fg.deobf("com.appliedenhancements:appliedenhancements:1.0.6-forge")
}
```

Alternatively, run `./gradlew publish` in the Applied Enhancements checkout to publish the complete mod to its local `repo` Maven directory. The coordinate is `com.appliedenhancements:appliedenhancements:1.0.6-forge`; its POM declares AE2 as a compile dependency and MixinExtras as a runtime dependency. Replace `flatDir` in the consuming project with `maven { url = uri("../AppliedEnhancements/repo") }`, adjust the checkout path, and retain Modrinth and Maven Central repositories. This task does not upload to a public Maven service.

If your integration unconditionally loads Applied Enhancements API classes, declare a required dependency in `mods.toml`:

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
mandatory=true
versionRange="[1.0.6-forge,1.1)"
ordering="AFTER"
side="BOTH"
```

If all API references are isolated behind an optional compatibility layer, declare an optional dependency instead:

```toml
[[dependencies.yourmod]]
modId="appliedenhancements"
mandatory=false
versionRange="[1.0.6-forge,1.1)"
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

There are 17 public top-level API types: 16 current types plus one deprecated compatibility entry point. Side labels describe where an integration should call them; the shared `api` package also contains APIs whose operations belong exclusively to the client.

| API | Side | Recommended lifecycle | Purpose |
|---|---|---|---|
| `AelisCraftingPlanner` | Server | Once per AE2 crafting calculation | Invokes AELIS explicitly |
| `MaxFastCraftingPlanner` (deprecated) | Server | Existing `1.0.3` integrations only | Retains the old planner interface, callbacks and result signatures while delegating to AELIS |
| `AelisCycleExecutionApi` | Server | CPU submission and execution | Attaches, copies, and reads cycle metadata; detects compatible CPUs |
| `AelisCycleExecutionPlan` | Server | Lifetime of one crafting plan | Describes proven cycle steps, seed floors, and protected keys |
| `AelisCycleSeedPolicy` | Server | Set when constructing a cycle plan | Selects minimum-seed preservation or maximum-throughput behavior |
| `AelisCycleRuntimeController` | Server | Lifetime of one running CPU job | Enforces cycle order and final-output retention |
| `AelisCycleAwareCpu` | Server | Implemented by a custom CPU | Declares support for AELIS cycle execution |
| `InfiniteStorageCellMarker` | Both | Implemented by a runtime storage type | Marks a runtime infinite-storage implementation |
| `InfiniteStorageCells` | Both | Data pack or runtime query | Exposes the public infinite-cell item tag |
| `PatternDuplicateApi` | Both | Register during Common Setup | Resolves outputs and finds duplicate or invalid patterns |
| `PatternOutputResolver` | Both | Register during Common Setup | Decodes outputs from third-party encoded patterns |
| `PatternTerminalIntegrationApi` | Client | Register during Client Setup | Adds support to a compatible pattern-terminal screen |
| `PatternBatchMoveApi` | Client and server | Register server handlers during Common Setup | Performs atomic pattern batch movement |
| `PatternQuickMoveSession` | Client only | Create once per open screen | Manages selection, cut buffers, and overlays |
| `NetworkItemContextMenuApi` | Client only | Register during Client Setup | Extends the ME network-item context menu |
| `PatternSlotRef` | Both | Current terminal session only | Identifies a machine container and pattern slot |
| `MolecularBalancedBatchProvider` | Server | Implemented by the provider type | Receives paired CPU scheduling-batch callbacks |

## Sides, registration and synchronization

| Integration | Registration and execution | Synchronization contract |
|---|---|---|
| Planner and cycle CPU | Calculate on the logical-server calculation's owning thread; mutate a running controller on its CPU's server thread | Results, callbacks, plans and snapshots are Java objects, not automatic network messages. A custom CPU owns its persistence and client status synchronization |
| Pattern output resolver | Register once during Common Setup on both physical client and dedicated server; use the caller's `Level` | Registries are local to each process. Matching registrations keep client filtering and server-side invalid-pattern checks consistent |
| Pattern-terminal and context-menu registrations | Register once during Client Setup; isolate screen classes from dedicated-server class loading | Registrations and actions stay on the client. Server changes use validated helpers or the integration's own validated payload |
| Batch movement | Register server handlers during Common Setup; call `requestMove` on the client or `execute` on the server | There is no public result callback or future. Observe authoritative menu updates, or provide your own result protocol |
| Infinite-cell item tag | Load server data-pack tags and query after tags are available | Minecraft synchronizes item tags. The Java marker interface is a local type capability, not a synchronization mechanism |

Use the same Applied Enhancements release build on client and server for its networked features; identical version strings alone do not establish matching development builds. Version `1.0.6-forge` uses SimpleChannel protocol `1.0.6-forge-1`. Built-in packets synchronize selected server feature settings, calculation progress and planner-path display, but not third-party registrations or custom CPU state. Payload classes are internal.

Registration APIs expose immutable snapshots but no unregister or replace operation. Do not register again on world load, screen opening, or every connection. Planner callbacks run in the calculation's context, possibly on worker threads; schedule UI or world work onto its owning thread.

### Migrating from the 1.0.3 planner API

This describes source migration. Integrations from 1.21.1 NeoForge must recompile with Java 17, Forge and AE2 15; the facade does not provide binary compatibility across Minecraft versions or loaders.

`MaxFastCraftingPlanner` remains as a deprecated compatibility facade for integrations compiled against `1.0.3`, including its `PauseCheckpoint`, `ProgressListener`, `Result`, `createConfigured(...)`, `create(...)` and `tryExecute(...)` signatures. It delegates planning to AELIS and does not restore the old implementation or rename the current UI/configuration back to MAX_FAST.

Its factory signatures remain `createConfigured(PauseCheckpoint, ProgressListener)` and `create(int, int, PauseCheckpoint, ProgressListener)`. `NO_PAUSE`, `ProgressListener.NONE`, all five progress callbacks and the eleven `Result` components retain their `1.0.3` types. This facade has no `ICraftingService` factory overload and is not scheduled for removal by its current deprecation annotation.

Use `AelisCraftingPlanner` for new development. When migrating source, update the planner type and its nested callback/result types together; they are distinct Java API types. Use the AELIS overload accepting `ICraftingService` when exact cycle candidates need recovery, and follow the cycle-plan submission and CPU protocol below. Legacy class linkage does not imply that an independent CPU has opted into the newer phase protocol.

## 1. AELIS planner

### Core rules

- Applied Enhancements' automatic planner integration is disabled by default, but public API calls are independent of `crafting.aelis.enable_automatic_planner`.
- Create one `AelisCraftingPlanner` instance for each AE2 crafting calculation.
- The same instance may be reused for the real and simulated attempts of that calculation.
- Planner instances are not thread-safe and must not be shared across calculation roots or parallel threads.
- The API operates on AE2 crafting-tree internals, so Applied Enhancements must be loaded and its corresponding Mixins must be active.

### Creating a planner

Use the server-configured node and compilation budgets:

```java
AelisCraftingPlanner planner = AelisCraftingPlanner.createConfigured(
        AelisCraftingPlanner.NO_PAUSE,
        AelisCraftingPlanner.ProgressListener.NONE);
```

Use integration-defined budgets and progress callbacks:

```java
AelisCraftingPlanner planner = AelisCraftingPlanner.create(
        100_000,
        2_000,
        () -> {
            // Optional cooperative pause point. Throw InterruptedException
            // when the calculation has been cancelled.
        },
        new AelisCraftingPlanner.ProgressListener() {
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

The complete factory set is `createConfigured(PauseCheckpoint, ProgressListener)`, its overload with a final `ICraftingService`, `create(int, int, PauseCheckpoint, ProgressListener)`, and its overload with a final `ICraftingService`. Null pause/listener arguments use no-op implementations; the service overload requires a non-null service. `ProgressListener` also exposes `compilationStep()`.

### Execution and fallback

```java
AelisCraftingPlanner.Result result = planner.tryExecute(
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
    // AELIS applied this request to the supplied simulation inventory and
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

### Planner provenance and cycle metadata for direct API calls

Successful API planning preserves the `AELIS` source label for both ordinary and cyclic plans, including when automatic integration is disabled.

When the requested output is also a cycle's startup seed, the solver may borrow only the proven missing startup amount from stock hidden by AE2's output-ignore operation. The borrowed amount is recorded as real plan input and must be returned in addition to the requested new output under both `PRESERVE_MINIMUM` and `MAX_THROUGHPUT`. Ordinary recipes still ignore existing output stock. Rejected branches and unsuccessful attempts restore borrowed inventory and extraction accounting; missing real seeds or other materials still prevent submission.

Direct API calls can produce cyclic plans, preserve seeds, and submit to supported CPUs while automatic integration is disabled. Use the overload accepting `ICraftingService` when recursion-hidden candidates must be recovered:

```java
var planner = AelisCraftingPlanner.createConfigured(
        AelisCraftingPlanner.NO_PAUSE,
        AelisCraftingPlanner.ProgressListener.NONE,
        grid.getCraftingService());
```

After `tryExecute(...)` returns `applied() == true`, the native `CraftingSimulationState.buildCraftingPlan(...)` method already includes the AELIS source label and any cycle metadata. Attach them explicitly when constructing a custom plan, including an ordinary plan:

```java
ICraftingPlan plan = buildCustomPlan();
plan = AelisCycleExecutionApi.attachToPlan(inventory, plan);
```

Copy metadata when replacing a plan without changing its cyclic patterns or firing counts:

```java
ICraftingPlan replacement = buildReplacementPlan(plan);
replacement = AelisCycleExecutionApi.copyMetadata(plan, replacement);
```

Always use the returned plan: custom implementations may be wrapped. These methods preserve the cycle schedule, seed policy, cyclic material amounts, and planner path while leaving the target plan's material accounting, requested output, and total logical work intact. Quantity wrappers around cyclic patterns are unwrapped and their factors are restored to firing counts; ordinary wrappers are retained. Replan if cyclic structure or counts change. Neither method enables automatic planning or manual inventory reservations.

## 2. Cycle-aware CPU execution

AE2's native CPU and AdvancedAE quantum CPUs are supported automatically. A custom `ICraftingCPU` that can receive AELIS cycle plans must implement `AelisCycleAwareCpu` and use the public runtime metadata:

```java
var cyclePlan = AelisCycleExecutionApi.getPlan(plan).orElse(null);
if (cyclePlan != null) {
    var runtime = AelisCycleRuntimeController.withCyclePhase(cyclePlan);
}
```

`AelisCycleExecutionPlan.seedPolicy()` selects cross-order behavior. `PRESERVE_MINIMUM` keeps `minimumSeeds()` active after the compressed schedule; `MAX_THROUGHPUT` does not reserve a post-order seed floor. Phase-aware execution still waits for tracked cycle outputs under either policy.

While a runtime has active seed protection:

- only `currentStep()` is an eligible cycle pattern;
- call `canDispatch(patternDefinition, possibleInputKeys)` before ordinary or cycle dispatch;
- call `patternDispatched(patternDefinition, crafts)` only for the active cycle step, using its actual accepted craft count, never for ordinary prerequisites or work after the cycle schedule;
- call `recordReturned(key, settledAmount)` after settling returned output, never for simulated insertion; AE2-style CPUs should use the actual decrease in waiting output, since standalone final output returns to ME even when its link accepts zero;
- use `requiredRetainedAmount(key)` or `maximumConsumableAmount(key, currentlyStored)` to prevent ordinary work from crossing the current seed floor;
- use `amountToRetain(key, incoming, currentlyStored)` before routing final output to the requester;
- persist `snapshot()` together with the execution plan and restore through `withCyclePhase(plan, state)`.

Complete plans retrieved through `getPlan`, `attachToPlan`, or `copyMetadata` receive `phase()` metadata containing ordinary prerequisites and actual cycle outputs. After explicitly opting in through `withCyclePhase`, `canDispatch` blocks unrelated ordinary work during this phase while allowing necessary prerequisites. `isComplete()` still means cyclic steps have been dispatched; `isCyclePhaseComplete()` additionally requires tracked outputs to return. Defer job cleanup until this phase completes even if the requested output has already been delivered, so final seed-restoration steps can finish. Custom persistence must retain `phase()` and the state's `pendingOutputs()` and restore through `withCyclePhase(plan, state)`.

The existing `new AelisCycleRuntimeController(plan)` and `(plan, state)` constructors retain legacy concurrent dispatch without a return barrier, so existing third-party CPUs that do not call `recordReturned` will not stall after upgrading. CPU integrations opting into `withCyclePhase` must also settle output returns and defer completion cleanup. Existing plan and state constructors remain available.

`getPlan(ICraftingPlan)` returns an `Optional<AelisCycleExecutionPlan>` and prepares missing phase metadata when the available graph is sufficient. Use its returned value rather than assuming the source carrier was mutated. If required patterns cannot be recovered, `phase()` can remain null and legacy scheduling is retained; the controller factory cannot reconstruct missing patterns by itself.

| Public cycle type | Data and validation |
|---|---|
| `AelisCycleExecutionPlan` | Canonical arguments: `List<Step> steps`, `Map<AEKey, Long> minimumSeeds`, `Set<AEKey> protectedKeys`, `AelisCycleSeedPolicy seedPolicy`, nullable `Phase phase`. Steps must be nonempty; amount entries must be positive. Collections are copied |
| `Step` | `AEKey patternDefinition`, positive `long crafts`, `Map<AEKey, Long> inputsPerCraft`, `Set<AEKey> selfReplenishingInputs`. Every input belongs to `protectedKeys`; self-replenishing keys belong to the step's inputs |
| `Phase` | `Set<AEKey> prerequisitePatterns`, `Map<AEKey, Map<AEKey, Long>> outputsPerPattern`. Output amounts are per firing, not totals for the order. A non-null phase must contain an output-map entry for every scheduled pattern definition |
| `AelisCycleRuntimeController.State` | `int stepIndex`, `long remainingCrafts`, `Map<AEKey, Long> pendingOutputs`. The two-argument constructor creates an empty pending map; completed state uses `stepIndex == steps.size()` and `remainingCrafts == 0` |
| `AelisCycleAwareCpu` | Default `supportsAelisCycleExecution()` returns true. `AelisCycleExecutionApi.supports(ICraftingCPU)` checks the capability; it does not install hooks |

The three-argument plan constructor retains `MAX_THROUGHPUT`; the four-argument constructor accepts a policy and starts without phase metadata. `patternDefinitions()` lists cyclic definitions. `runtime.plan()`, `isCyclePattern(AEKey)`, `currentStep()` and `remainingCrafts()` support dispatch inspection. `canDispatch` does not itself extract or validate inventory.

`withCyclePhase(plan, state)` validates the pending-output map against already dispatched work: completed steps count in full, the current step contributes only `step.crafts() - state.remainingCrafts()`, and future steps contribute nothing. Every pending key must be explainable by those outputs, with a positive amount no greater than their total production. A null `phase()` requires an empty pending map. The legacy constructors deliberately discard phase metadata and pending-output entries rather than restoring the new protocol; never use them to load a phase-aware saved job.

Make returned products available for subsequent cyclic dispatch, using `amountToRetain` to retain final-output material needed by remaining cycles. Batch size is the minimum supported by all current inputs, `remainingCrafts()`, and the provider's safe limit. Advance by the actual dispatched count; do not fix batches to initial seed stock or add unplanned work.

Under `PRESERVE_MINIMUM`, planning already adds enough cycle production for the requested final output plus the minimum startup vector. Keep that vector in the CPU inventory until the job finishes so the normal CPU cleanup returns it to ME storage. Do not decrement or replace the requested final-output amount with the reserve.

Advance the controller immediately before calling a provider that may return outputs synchronously. If the provider rejects or throws, restore the previous snapshot. This prevents synchronous output from being evaluated against a stale cycle step.

Do not implement only the marker interface. A CPU that advertises the capability but ignores ordering, protected inputs, final-output retention, or persistence can still deadlock.

The following Data Energistics integration protocol is retained in source and has not been runtime-validated for this Forge branch. The built-in native and quantum CPU integrations support no-output completion for marked Data Energistics order packages with both automatic planning and direct API submission. Quantum CPUs record only the actual package output registered after successful dispatch; native CPUs align DE's existing completion records with batch counts. Settling a virtual package still requires the cycle phase to finish before job cleanup. Manual cancellation remains immediate. Independent CPUs implementing virtual outputs must preserve DE's semantics instead of materializing packages or treating requester acceptance as the completion record.

### Public CPU integration helpers

External CPU integrations can perform normalization, guarded input access and persistence through `AelisCycleExecutionApi` without importing implementation classes from `runtime`:

| Method | Contract |
|---|---|
| `ICraftingPlan preparePlan(ICraftingPlan plan)` | Returns the plan to submit, with cyclic quantity wrappers normalized and metadata retained/prepared. Always use the returned plan |
| `Map<AEKey, Long> getCyclicCraftAmounts(ICraftingPlan plan)` | Immutable cyclic-material amounts; empty when absent. This is not the runtime pending-output map |
| `ICraftingInventory guardInputs(AelisCycleRuntimeController runtime, AEKey patternDefinition, ICraftingInventory inventory)` | Nullable runtime is allowed and returns the original inventory. A null result means dispatch is forbidden. Use the returned inventory for the entire extraction, including additional batch inputs and rollback |
| `long dispatchedCrafts(AelisCycleRuntimeController runtime, AEKey patternDefinition, KeyCounter[] inputs)` | Actual firing count represented by aggregated inputs for the active cycle step; returns zero for non-current work. Inputs and holders must be non-null and each protected input must imply the same complete positive count within the remaining step |
| `CompoundTag writeRuntime(AelisCycleRuntimeController runtime)` | Serializes the plan, phase and pending-output state of a non-null runtime |
| `Optional<AelisCycleRuntimeController> readRuntime(CompoundTag tag)` | Restores supported runtime formats, including legacy v1. Empty indicates missing, invalid or unsupported state; do not silently resume a job that had saved cycle metadata as an ordinary order |

Forge/AE2 15 serializes keys through static item/fluid registries. Prefer the overloads above without a registry parameter. The `HolderLookup.Provider` overloads remain available, reject a null provider and delegate to the same implementation.

Call `preparePlan` before constructing the CPU's task map; reading `getPlan` alone does not replace that map. A guarded inventory belongs to one extraction attempt and cannot be cached across pattern changes or runtime advancement. `dispatchedCrafts` does not advance the controller. A step with no protected inputs is valid, but this helper cannot infer its actual firing count; the host must provide a verified count bounded by `remainingCrafts()` before calling `patternDispatched`.

```java
plan = AelisCycleExecutionApi.preparePlan(plan);
var runtime = AelisCycleExecutionApi.getPlan(plan)
        .map(AelisCycleRuntimeController::withCyclePhase)
        .orElse(null);

// Save only when this CPU still owns the corresponding job.
if (runtime != null) {
    jobTag.put("cycleRuntime",
            AelisCycleExecutionApi.writeRuntime(runtime));
}

// On load, an existing but invalid tag is an error requiring safe job handling.
if (jobTag.contains("cycleRuntime")) {
    var restored = AelisCycleExecutionApi.readRuntime(
            jobTag.getCompound("cycleRuntime"));
    if (restored.isEmpty()) {
        rejectOrCancelSavedJobSafely(); // Host-specific handling; preserve inventories.
        return;
    }
    runtime = restored.get();
}
```

For each insertion, retain the job's waiting-inventory reference before calling host code, because that code may finish the job. After a real insertion, report `max(0, min(offeredAmount, waitingBefore - waitingAfter))` to `recordReturned`; never report simulation results or count the same returned output twice. These helpers do not install CPU callbacks, submit jobs, or implement third-party virtual-output completion automatically.

## 3. Infinite storage markers

### Item tag

Item-backed storage cells should prefer the public tag:

```text
#appliedenhancements:infinite_storage_cells
```

Create this resource in the integrating mod:

```text
src/main/resources/data/appliedenhancements/tags/items/infinite_storage_cells.json
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

`isMarked` returns false for a null or empty stack. The runtime marker has no methods and must be implemented by the storage inventory itself; implementing it only on the cell item does not mark the mounted inventory.

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

## 4. Third-party encoded patterns and pattern filters

### Registering an output resolver

Register once from Common Setup through `enqueueWork`:

```java
event.enqueueWork(() -> PatternDuplicateApi.registerOutputResolver(
        new ResourceLocation("examplemod", "custom_patterns"),
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

### Using duplicate and invalid-pattern detection directly

```java
List<PatternDuplicateApi.PatternEntry> entries = collectEntries();

Map<PatternSlotRef, AEKey> outputs =
        PatternDuplicateApi.indexPrimaryOutputs(entries, level);

Set<PatternSlotRef> duplicateSlots =
        PatternDuplicateApi.findDuplicateSlots(outputs);

Set<PatternSlotRef> invalidSlots =
        PatternDuplicateApi.findInvalidSlots(entries, level);
```

Integrations with their own stable grouping key can use the generic overload:

```java
Set<PatternSlotRef> duplicates =
        PatternDuplicateApi.findDuplicateSlots(customSlotToKeyMap);
```

`PatternSlotRef.containerId()` is the server ID AE2 assigns to the machine container, not a menu slot index. A reference is valid only while the current terminal remains open.

Its constructor is `PatternSlotRef(long containerId, int slot)`; `slot` must be non-negative. Constructing the record does not establish that the container or slot exists or that the player can access it.

`PatternDuplicateApi.isInvalidPattern(...)` reports encoded patterns that neither AE2 nor a registered custom output resolver can resolve. `outputMatchesSearch(...)` checks the localized display names of all resolved output keys against a lowercase filter.

The direct query methods are `resolveOutputs(ItemStack, Level)`, `primaryOutput(ItemStack, Level)`, `isInvalidPattern(ItemStack, Level)`, and `outputMatchesSearch(ItemStack, Level, String)`. `registeredOutputResolvers()` returns a registration snapshot. `findDuplicateSlots` accepts either `(Collection<PatternEntry>, Level)` or `Map<PatternSlotRef, K>`; `findInvalidSlots` and `indexPrimaryOutputs` accept `(Collection<PatternEntry>, Level)`. `PatternEntry(PatternSlotRef, ItemStack)` stores the supplied stack reference, so copy a mutable stack yourself when a retained snapshot is required. Null/empty stacks or a null level resolve to no outputs; non-pattern items are not automatically reported as invalid. Search normalizes its filter using `Locale.ROOT`.

## 5. Compatible pattern-terminal registration

Register the exact client screen class name during Client Setup:

```java
event.enqueueWork(() -> PatternTerminalIntegrationApi.register(
        new ResourceLocation("examplemod", "pattern_terminal"),
        PatternTerminalIntegrationApi.Family.AE2_PATTERN_ACCESS,
        "examplemod.client.gui.ExamplePatternAccessScreen"));
```

Available layout families:

| `Family` | Requirements |
|---|---|
| `AE2_PATTERN_ACCESS` | The screen must extend the AE2 Pattern Access Terminal and preserve its slot-row and machine-header layout |
| `EXTENDEDAE_PATTERN_ACCESS` | The screen must extend the ExtendedAE terminal and preserve its corresponding layout |

A compatible registered terminal receives duplicate and invalid-pattern filtering, Quick Move, box selection, the right-click context menu, and machine-group Cut/Paste controls. Duplicate, invalid-pattern, and Quick Move modes are mutually exclusive.

AE2-family screens inherit the dedicated second toolbar row used by the built-in Pattern Access Terminal, keeping the localized title and search field unobstructed. ExtendedAE-family screens retain their existing top-row control layout.

Built-in registrations already cover:

- AE2 Pattern Access Terminal;
- AE2WTLib Wireless Pattern Access Terminal;
- ExtendedAE Extended Pattern Access Terminal;
- ExtendedAE Wireless Extended Pattern Access Terminal.

Registration IDs must be unique, and `screenClassName` must be the exact runtime class name rather than a superclass name.

`registrations()` exposes a snapshot; `supports(Object screen, Family)` and `supports(String screenClassName, Family)` query exact registrations. A registered base class does not automatically register every subclass.

Fully custom row models cannot obtain the feature by registration alone. They should use `PatternDuplicateApi`, `PatternBatchMoveApi`, and `PatternQuickMoveSession` directly in their own UI implementation.

## 6. Atomic pattern batch movement

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
        new ResourceLocation("examplemod", "pattern_menu"),
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

`MenuExtension` takes precedence over registered handlers. Higher-priority handlers are checked first, with ties ordered by ID. The first handler whose `supports(menu)` returns `true` owns the request; a failure does not fall through to another handler. IDs must be unique, and `registeredMenuHandlers()` returns a snapshot.

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
| `sources` | Source `PatternSlotRef` values; `1..512` |
| `targetContainerIds` | Candidate target machine container IDs; `1..128` |
| `preferredTargetSlot` | Preferred destination slot; use `-1` for automatic placement |

Client data is untrusted. The server handler must revalidate the menu ID, player access, source contents, target capacity, and same-source targets, then guarantee all-or-nothing behavior.

The built-in `PatternAccessTermMenu` handler uses `PatternDuplicateApi.isInvalidPattern(...)` to skip unresolvable encoded patterns before planning the transaction. Those patterns remain in their original slots, while every remaining valid source is still committed atomically. Custom handlers can adopt the same policy when they need matching behavior.

`PatternBatchMoveApi` catches handler runtime exceptions and returns `APPLY_FAILED`, but a custom handler remains responsible for restoring any inventory state it modified before throwing.

Use the provided `Result.success(...)` and `Result.failure(...)` factories so moved counts and failure reasons remain internally consistent.

`execute(ServerPlayer, Request)` returns `Result` synchronously on the server. `Request(int menuId, List<PatternSlotRef> sources, List<Long> targetContainerIds, int preferredTargetSlot)` copies both lists, requires a non-negative menu ID and a preferred slot of at least `-1`; `copySources(Collection)` and `copyTargets(Collection)` expose the same nonempty size checks. `Failure` is one of `NONE`, `INVALID_MENU`, `INVALID_SOURCE`, `INVALID_TARGET`, `SAME_TARGET`, `NOT_ENOUGH_SPACE`, or `APPLY_FAILED`. Failure results move zero patterns. The built-in client payload reports the server result as a system message; `requestMove(...)` itself returns void.

## 7. Custom client Quick Move sessions

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

Disabling the session clears selection and the cut buffer. `clear()` clears buffered state without changing `enabled()`; use `setEnabled(false)` when leaving Quick Move mode. A normal box selection replaces the previous selection; `beginSelection(..., true)` subtracts from the existing selection, and ordinary individual clicks toggle one slot. Exceeding the batch-move bounds does not submit an oversized request.

`enabled()`, `selectedCount()`, `cutCount()`, `hasCutBuffer()` and `isSelected(PatternSlot, Map<PatternSlotRef, PatternSlotRef>)` expose session state. `cutGroup(Collection<PatternContainerRecord>)` returns the buffered count; `paste(int, List<Long>, int)` returns whether a request was sent, not whether the server committed it. `finishSelection` and rendering accept `Collection<Slot>`, but selectable entries must still be AE2 `PatternSlot` instances. Mouse/selection-box coordinates are screen coordinates; selected-slot overlays use menu-local coordinates and the screen's usual GUI translation.

## 8. Network-item context-menu extensions

Starting with `1.0.5`, built-in item-action menus use their own configurable binding, **Open Item Actions Menu**, defaulting to **Alt + right-click**. Pattern Quick Move Cut/Paste uses **Open Pattern Quick Move Menu**, defaulting to **right-click**. Both bindings honor Forge modifiers and GUI context. The old shared binding is retained for pattern Quick Move, so a saved right-click mapping does not overwrite the new item-action default. Compatible registered screens receive this separation automatically; registering menu entries does not change either binding. Custom screens own their input routing and must keep the two actions separate. This Forge build uses SimpleChannel protocol `1.0.6-forge-1`; client and server must install the same version.

Register entry providers during Client Setup:

```java
event.enqueueWork(() -> NetworkItemContextMenuApi.register(
        new ResourceLocation("examplemod", "inspect_item"),
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

`EntryProvider.createEntries(Context)` returns a list; null lists and entries are skipped. `Entry(Component label, Consumer<Context> action)` requires both arguments, and `activate(Context)` invokes that client action. `registrations()` returns the registration snapshot and `entries(Context)` builds a fresh immutable list of custom entries. A context belongs to the current menu invocation and must not be treated as a durable server inventory handle.

`Context` exposes `extractOne()`, `extractStack()`, `extractAmount(long)`, `requestCraft()`, `copyName()`, `copyId()`, and `searchSameMod()`. A third-party action with its own server mutation must send a dedicated payload and revalidate the active menu, permissions, resource key, and amount; the client-side menu entry is never authoritative.

## 9. Provider scheduling-batch callbacks

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

The built-in callback scope currently belongs to the native AE2 CPU. AdvancedAE quantum cycle support does not by itself provide this separate scheduling-batch callback API. An independent CPU must explicitly provide equivalent pairing if it wants to support these callbacks; implementing the provider interface does not automatically increase batch size or enable smart multiplication.

Implementations should release batch state in a `finally` block and should not retain the input snapshot after the batch ends unless they make their own copy.

## KubeJS boundary

KubeJS is supported only for adding item IDs to the infinite-storage-cell tag. There is no KubeJS API for:

- AELIS planner sessions;
- encoded-pattern Java resolver registration;
- pattern-terminal screen registration;
- network-item context-menu registration;
- atomic batch movement handlers;
- provider scheduling-batch callbacks.

These features require AE2 Java types, client UI integration, or server-authoritative transactional validation and must be implemented by a Java mod.

## Pre-release integration checklist

See the [release validation scope](../README.md#validation) for the Forge build, `362` unit tests, and separate modpack records for ordinary crafting, AELIS and pattern management. Earlier NeoForge GameTests do not validate this branch; custom CPUs must still verify cycle execution, persistence, virtual outputs and dedicated-server integration.

- [ ] Imports are limited to the stable API packages.
- [ ] Optional compatibility classes cannot load when Applied Enhancements is absent.
- [ ] Client APIs are referenced only from client classes and Client Setup.
- [ ] Registration IDs use the integrating mod's namespace and are unique.
- [ ] AELIS planner instances are not shared across calculations or threads.
- [ ] Normal planner fallback continues through another planner or AE2's native path.
- [ ] Custom plans use the return value of `attachToPlan` or `copyMetadata` to retain the AELIS source label and cycle metadata.
- [ ] Requested-output seed tests verify the full new output plus borrowed-seed return, and reject orders with real missing inputs.
- [ ] Batch movement revalidates every client-supplied field on the server.
- [ ] Batch movement restores every source and target after failure.
- [ ] `PatternQuickMoveSession` is cleared when its screen closes.
- [ ] Custom network-item menu server actions revalidate every client-supplied field.
- [ ] Infinite markers are applied only to storage implementations that are already infinite.
- [ ] The integration has been tested with AE2 `15.4.10` and the target modpack on both client and server.
