package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.appliedenhancements.api.*;
import com.appliedenhancements.test.TestAEKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisBatchExecutionContextTest {
    private final AEKey pattern = new TestAEKey("pattern");
    private final AEKey next = new TestAEKey("next");
    private final AEKey seed = new TestAEKey("seed");
    private final AEKey output = new TestAEKey("output");

    private AelisCycleRuntimeController runtime(boolean protectedInputs) {
        return AelisCycleRuntimeController.withCyclePhase(new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(pattern, 3, protectedInputs ? Map.of(seed, 2L) : Map.of(), Set.of()),
                new AelisCycleExecutionPlan.Step(next, 1, Map.of(seed, 1L), Set.of())),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM,
                new AelisCycleExecutionPlan.Phase(Set.of(), Map.of(pattern, Map.of(output, 4L), next, Map.of(seed, 1L)))));
    }

    private KeyCounter[] inputs(long amount) {
        var holder = new KeyCounter();
        holder.add(seed, amount);
        return new KeyCounter[]{holder};
    }

    @Test void explicitRuntimeSupportsIndependentCpuWithoutInternalScope() {
        var runtime = runtime(true);
        var stock = new ListCraftingInventory(ignored -> {});
        stock.insert(seed, 200, Actionable.MODULATE);
        var context = AelisBatchExecutionContext.acquire(runtime, pattern, stock);
        assertTrue(context.hasCycleProtection());
        assertEquals(3, context.maximumCrafts());
        assertEquals(6, context.inventory().extract(seed, 200, Actionable.SIMULATE));
        assertNull(AelisBatchExecutionContext.acquire(runtime, next, stock).inventory());
        var before = runtime.snapshot();
        try (var rejected = context.beginDispatch(inputs(4), 2)) {}
        assertEquals(before, runtime.snapshot());
        try (var accepted = context.beginDispatch(inputs(6), 3)) { accepted.accepted(); }
        assertEquals(12, runtime.snapshot().pendingOutputs().get(output));
    }

    @Test void taskTotalCannotOverrideCurrentStepAndAllExtractionsShareOneView() {
        var runtime = runtime(true);
        var stock = new ListCraftingInventory(ignored -> {});
        stock.insert(seed, 200, Actionable.MODULATE);
        try (var scope = AelisCycleDispatchScope.open(runtime)) {
            var context = AelisBatchExecutionContext.acquire(pattern, stock);
            assertEquals(3, Math.min(100, context.maximumCrafts()));
            var view = context.inventory();
            assertEquals(2, view.extract(seed, 2, Actionable.MODULATE));
            assertEquals(4, view.extract(seed, 198, Actionable.MODULATE));
            assertEquals(0, view.extract(seed, 1, Actionable.SIMULATE));
            view.insert(seed, 6, Actionable.MODULATE);
            assertEquals(6, view.extract(seed, 200, Actionable.SIMULATE));
            assertEquals(200, stock.extract(seed, 200, Actionable.SIMULATE));
            assertNull(AelisBatchExecutionContext.acquire(next, stock).inventory());
        }
    }

    @Test void legacyProviderHookDoesNotAdvanceTwiceEvenOnFinalStepFiring() {
        var runtime = runtime(true);
        var inputs = inputs(6);
        try (var scope = AelisCycleDispatchScope.open(runtime)) {
            var context = AelisBatchExecutionContext.acquire(pattern, new ListCraftingInventory(ignored -> {}));
            try (var batch = context.beginDispatch(inputs, 3)) {
                var after = runtime.snapshot();
                assertEquals(next, runtime.currentStep().orElseThrow().patternDefinition());
                try (var nativeHook = AelisBatchExecutionContext.beginProviderDispatch(runtime, pattern, inputs)) {
                    nativeHook.accepted();
                }
                assertEquals(after, runtime.snapshot());
                batch.accepted();
            }
            assertEquals(12, runtime.snapshot().pendingOutputs().get(output));
        }
    }

    @Test void apiRejectionAndExceptionRestoreProgressAndPendingReturnsForRetry() {
        var runtime = runtime(true);
        var before = runtime.snapshot();
        try (var scope = AelisCycleDispatchScope.open(runtime)) {
            var context = AelisBatchExecutionContext.acquire(pattern, new ListCraftingInventory(ignored -> {}));
            try (var rejected = context.beginDispatch(inputs(4), 2)) {
                runtime.recordReturned(output, 4);
            }
            assertEquals(before, runtime.snapshot());
            assertThrows(IllegalArgumentException.class, () -> {
                try (var failed = context.beginDispatch(inputs(4), 2)) {
                    throw new IllegalArgumentException("provider failed before ownership");
                }
            });
            assertEquals(before, runtime.snapshot());
            try (var retry = context.beginDispatch(inputs(6), 3)) { retry.accepted(); }
            assertEquals(12, runtime.snapshot().pendingOutputs().get(output));
        }
    }

    @Test void apiAcceptanceRegistersOutputsBeforeSynchronousReturnAndSurvivesCleanupFailure() {
        var runtime = runtime(true);
        try (var scope = AelisCycleDispatchScope.open(runtime)) {
            var context = AelisBatchExecutionContext.acquire(pattern, new ListCraftingInventory(ignored -> {}));
            assertThrows(IllegalArgumentException.class, () -> {
                try (var batch = context.beginDispatch(inputs(4), 2)) {
                    runtime.recordReturned(output, 8);
                    batch.accepted();
                    throw new IllegalArgumentException("cleanup after ownership");
                }
            });
            assertEquals(1, runtime.remainingCrafts());
            assertTrue(runtime.snapshot().pendingOutputs().isEmpty());
            assertEquals(1, AelisBatchExecutionContext.acquire(pattern, context.inventory()).maximumCrafts());
        }
    }

    @Test void explicitCountSupportsStepsWithoutProtectedInputsButRejectsMalformedBatchesBeforeOwnership() {
        var runtime = runtime(false);
        try (var scope = AelisCycleDispatchScope.open(runtime)) {
            var context = AelisBatchExecutionContext.acquire(pattern, new ListCraftingInventory(ignored -> {}));
            assertThrows(IllegalStateException.class, () -> context.beginDispatch(new KeyCounter[0], 4));
            try (var batch = context.beginDispatch(new KeyCounter[0], 3)) { batch.accepted(); }
            assertEquals(next, runtime.currentStep().orElseThrow().patternDefinition());
        }
        runtime = runtime(true);
        var before = runtime.snapshot();
        try (var scope = AelisCycleDispatchScope.open(runtime)) {
            var context = AelisBatchExecutionContext.acquire(pattern, new ListCraftingInventory(ignored -> {}));
            assertThrows(IllegalStateException.class, () -> context.beginDispatch(inputs(3), 2));
            assertThrows(IllegalStateException.class, () -> context.beginDispatch(inputs(4), 3));
            assertEquals(before, runtime.snapshot());
        }
    }

    @Test void innerLegacyHookOrApiOwnershipSurvivesFailureInOuterCleanup() {
        for (boolean api : new boolean[]{false, true}) {
            var runtime = runtime(true);
            var inputs = inputs(4);
            try (var scope = AelisCycleDispatchScope.open(runtime)) {
                var context = AelisBatchExecutionContext.acquire(pattern, new ListCraftingInventory(ignored -> {}));
                assertThrows(IllegalArgumentException.class, () -> {
                    try (var outer = context.beginDispatch(inputs, 2)) {
                        if (api) AelisBatchExecutionContext.acceptCurrentDispatch();
                        else try (var nativeHook = AelisBatchExecutionContext.beginProviderDispatch(runtime, pattern, inputs)) {
                            nativeHook.accepted();
                        }
                        throw new IllegalArgumentException("cleanup after durable ownership");
                    }
                });
                assertEquals(1, runtime.remainingCrafts());
                assertEquals(8, runtime.snapshot().pendingOutputs().get(output));
            }
        }
    }

    @Test void ordinaryWorkHasNoLimitAndScopeDoesNotLeak() {
        var stock = new ListCraftingInventory(ignored -> {});
        var context = AelisBatchExecutionContext.acquire(pattern, stock);
        assertSame(stock, context.inventory());
        assertEquals(Long.MAX_VALUE, context.maximumCrafts());
        assertFalse(context.hasCycleProtection());
        try (var scope = AelisCycleDispatchScope.open(runtime(true))) {
            assertEquals(3, AelisBatchExecutionContext.acquire(pattern, stock).maximumCrafts());
        }
        assertFalse(AelisBatchExecutionContext.acquire(pattern, stock).hasCycleProtection());
    }
}
