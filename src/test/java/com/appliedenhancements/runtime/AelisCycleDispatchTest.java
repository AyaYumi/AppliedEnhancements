package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.appliedenhancements.test.TestAEKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisCycleDispatchTest {
    private final AEKey pattern = new TestAEKey("cycle_pattern");
    private final AEKey nextPattern = new TestAEKey("next_pattern");
    private final AEKey ordinaryPattern = new TestAEKey("ordinary_pattern");
    private final AEKey seed = new TestAEKey("seed");
    private final AEKey external = new TestAEKey("external");

    private AelisCycleRuntimeController runtime() {
        return new AelisCycleRuntimeController(new AelisCycleExecutionPlan(
                List.of(new AelisCycleExecutionPlan.Step(pattern, 3, Map.of(seed, 2L), Set.of(seed)),
                        new AelisCycleExecutionPlan.Step(nextPattern, 1, Map.of(seed, 1L), Set.of())),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM));
    }

    @Test
    void rejectsFutureCyclePattern() {
        assertNull(AelisCycleDispatch.inventory(runtime(), nextPattern, new ListCraftingInventory(ignored -> {})));
    }

    @Test
    void limitsAggregatedInputsToRemainingStepAndAccountsForRollback() {
        var inventory = new ListCraftingInventory(ignored -> {});
        inventory.insert(seed, 100, Actionable.MODULATE);
        var guarded = AelisCycleDispatch.inventory(runtime(), pattern, inventory);
        assertEquals(6, guarded.extract(seed, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(4, guarded.extract(seed, 4, Actionable.MODULATE));
        assertEquals(2, guarded.extract(seed, 100, Actionable.SIMULATE));
        guarded.insert(seed, 4, Actionable.MODULATE);
        assertEquals(6, guarded.extract(seed, 100, Actionable.SIMULATE));
        assertEquals(100, inventory.extract(seed, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Test
    void ordinaryPrerequisiteCanRunWhileAnotherProtectedMaterialIsAbsent() {
        var inventory = new ListCraftingInventory(ignored -> {});
        inventory.insert(external, 32, Actionable.MODULATE);
        var guarded = AelisCycleDispatch.inventory(runtime(), ordinaryPattern, inventory);
        assertEquals(32, guarded.extract(external, 32, Actionable.MODULATE));
        assertEquals(0, guarded.extract(seed, 1, Actionable.SIMULATE));
    }

    @Test
    void ordinaryConsumptionPreservesSeedFloorAfterCycleCompletes() {
        var runtime = runtime();
        runtime.patternDispatched(pattern, 3);
        runtime.patternDispatched(nextPattern, 1);
        var inventory = new ListCraftingInventory(ignored -> {});
        inventory.insert(seed, 10, Actionable.MODULATE);
        var guarded = AelisCycleDispatch.inventory(runtime, ordinaryPattern, inventory);
        assertEquals(9, guarded.extract(seed, 100, Actionable.MODULATE));
        assertEquals(1, inventory.extract(seed, 100, Actionable.SIMULATE));
    }

    @Test
    void batchAdvancesByActualCraftCountAndRejectsOverdispatch() {
        var inputs = new KeyCounter();
        inputs.add(seed, 6);
        assertEquals(3, AelisCycleDispatch.dispatchedCrafts(runtime(), pattern, new KeyCounter[] { inputs }));
        inputs.add(seed, 2);
        assertThrows(IllegalStateException.class, () ->
                AelisCycleDispatch.dispatchedCrafts(runtime(), pattern, new KeyCounter[] { inputs }));
    }

    @Test
    void ordinaryJobsUseUnmodifiedInventory() {
        var inventory = new ListCraftingInventory(ignored -> {});
        assertSame(inventory, AelisCycleDispatch.inventory(null, ordinaryPattern, inventory));
        assertEquals(0, AelisCycleDispatch.dispatchedCrafts(null, ordinaryPattern, new KeyCounter[0]));
    }

    @Test
    void allProtectedInputsMustRepresentTheSameWholeCraftCount() {
        var runtime = new AelisCycleRuntimeController(new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(pattern, 3, Map.of(seed, 1L, external, 2L), Set.of())),
                Map.of(seed, 1L), Set.of(seed, external)));
        var seedHolder = new KeyCounter();
        seedHolder.add(seed, 2);
        var externalHolder = new KeyCounter();
        externalHolder.add(external, 2);
        assertThrows(IllegalStateException.class, () -> AelisCycleDispatch.dispatchedCrafts(
                runtime, pattern, new KeyCounter[] {seedHolder, externalHolder}));
        externalHolder.add(external, 2);
        assertEquals(2, AelisCycleDispatch.dispatchedCrafts(
                runtime, pattern, new KeyCounter[] {seedHolder, externalHolder}));
        externalHolder.add(external, 1);
        assertThrows(IllegalStateException.class, () -> AelisCycleDispatch.dispatchedCrafts(
                runtime, pattern, new KeyCounter[] {seedHolder, externalHolder}));
    }

    @Test
    void stepsWithoutInternalInputsNeedExplicitPatternInformationForBatchCounting() {
        var runtime = new AelisCycleRuntimeController(new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(pattern, 1, Map.of(), Set.of())), Map.of(), Set.of()));
        var failure = assertThrows(IllegalStateException.class, () -> AelisCycleDispatch.dispatchedCrafts(
                runtime, pattern, new KeyCounter[0]));
        assertTrue(failure.getMessage().contains("actual pattern inputs"));
    }

    @Test
    void nativeProviderPushAdvancesOneCraftWithoutProtectedInputs() {
        var runtime = new AelisCycleRuntimeController(new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(pattern, 2, Map.of(), Set.of())), Map.of(), Set.of()));
        assertEquals(1, AelisCycleDispatch.dispatchedProviderPush(
                runtime, pattern, new KeyCounter[0]));
        runtime.patternDispatched(pattern, 1);
        assertEquals(1, runtime.remainingCrafts());
    }
}
