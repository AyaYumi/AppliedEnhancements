package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.*;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;
import com.github.appliedenhancements.crafting.aelis.AelisPlanner;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningFallbackTest {
    @Test void outOfOrderAndRepeatedScopeClosesKeepTheActiveInnerScope() throws Exception {
        var field = CraftingPlannerIntervention.class.getDeclaredField("EXPLICIT");
        field.setAccessible(true);
        var scopes = (ThreadLocal<?>) field.get(null);
        var outer = CraftingPlannerIntervention.openExplicit();
        var inner = CraftingPlannerIntervention.openExplicit();
        assertDoesNotThrow(outer::close);
        assertSame(inner, scopes.get());
        assertDoesNotThrow(inner::close);
        assertNull(scopes.get());
        assertDoesNotThrow(inner::close);
    }

    @Test void aForeignCloseCannotClearTheOwnerOrTheForeignThreadScope() throws Exception {
        var field = CraftingPlannerIntervention.class.getDeclaredField("EXPLICIT");
        field.setAccessible(true);
        var scopes = (ThreadLocal<?>) field.get(null);
        var owner = CraftingPlannerIntervention.openExplicit();
        var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try (var own = CraftingPlannerIntervention.openExplicit()) {
                owner.close();
                assertSame(own, scopes.get());
            } catch (Throwable failure) { failed.set(failure); }
        });
        thread.start();
        thread.join();
        assertNull(failed.get());
        assertSame(owner, scopes.get());
        owner.close();
        assertNull(scopes.get());
    }

    @Test void incompatibleOptionalTreeUsesNativeBuildAndTooltip() {
        assertNull(assertDoesNotThrow(() -> ExactCraftingTree.build(new Object(), false)));
        var tooltip = List.<net.minecraft.network.chat.Component>of();
        assertSame(tooltip, assertDoesNotThrow(() -> ExactCraftingTree.tooltip(new Object(), 0, 0, tooltip)));
    }

    @Test void failingCompilationObserverReturnsFallbackBeforeMutatingInventory() throws InterruptedException {
        var inventory = new CraftingSimulationState() {
            @Override protected long simulateExtractParent(AEKey key, long amount) { return 0; }
            @Override protected Iterable<AEKey> findFuzzyParent(AEKey key) { return List.of(); }
        };
        for (boolean linkage : new boolean[]{false, true}) {
            var observer = new AelisPlanner.ProgressSink() {
                @Override public void compilationStarted() {
                    if (linkage) throw new NoClassDefFoundError("Optional progress integration");
                    throw new IllegalStateException("Optional observer rejected its state");
                }
            };
            var missing = new KeyCounter();
            var result = new AelisPlanner.Session(1_000, 100, () -> {}, observer)
                    .tryExecute(null, inventory, 1, false, missing);
            assertFalse(result.applied());
            assertNotNull(result.error());
            assertTrue(missing.isEmpty());
        }
    }
}
