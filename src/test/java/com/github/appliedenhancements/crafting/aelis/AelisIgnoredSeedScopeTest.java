package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.mixin.AelisChildSimulationStateAccessor;
import com.appliedenhancements.mixin.CraftingSimulationStateLongSafetyAccessor;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisIgnoredSeedInventory;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AelisIgnoredSeedScopeTest {
    private final AEKey seed = new TestAEKey("ignored_root_seed");

    @Test void borrowsOnlyProvenSeedAndChargesRealNetworkInput() {
        var root = new State(null, Map.of(seed, 64L)); root.ignore(seed);
        var child = new State(root, Map.of()); assertEquals(0, child.extract(seed, 100, Actionable.SIMULATE));
        try (var transaction = new AelisIgnoredSeedScope(root); var lease = AelisIgnoredSeedScope.lease(child)) {
            assertEquals(1, lease.expose(seed, 1));
            assertEquals(1, child.extract(seed, 100, Actionable.MODULATE));
            child.insert(seed, 1, Actionable.MODULATE);
            child.applyDiff(root); lease.commit(); transaction.commit();
        }
        assertEquals(1, root.appliedenhancements$getRequiredExtract().get(seed));
        assertEquals(1, root.extract(seed, 100, Actionable.SIMULATE));
        assertTrue(root.ignored.isEmpty()); // The other 63 existing outputs remain hidden.
    }

    @Test void rejectedCyclicBranchRestoresIgnoreBeforeNativeFallback() {
        var root = new State(null, Map.of(seed, 64L)); root.ignore(seed);
        try (var transaction = new AelisIgnoredSeedScope(root)) {
            try (var lease = AelisIgnoredSeedScope.lease(root)) {
                assertEquals(1, lease.expose(seed, 1));
                assertEquals(1, root.extract(seed, 1, Actionable.MODULATE));
            }
            assertEquals(0, root.extract(seed, 100, Actionable.SIMULATE));
            assertEquals(0, root.appliedenhancements$getRequiredExtract().get(seed));
            transaction.commit();
        }
        assertEquals(64, root.ignored.get(seed));
    }

    @Test void outerFailureRollsBackAnAlreadyAcceptedInnerLease() {
        var root = new State(null, Map.of(seed, 1L)); root.ignore(seed);
        assertThrows(IllegalStateException.class, () -> {
            try (var transaction = new AelisIgnoredSeedScope(root); var lease = AelisIgnoredSeedScope.lease(root)) {
                lease.expose(seed, 1); root.extract(seed, 1, Actionable.MODULATE); lease.commit();
                throw new IllegalStateException("later branch failed");
            }
        });
        assertEquals(0, root.extract(seed, 100, Actionable.SIMULATE));
        assertEquals(0, root.appliedenhancements$getRequiredExtract().get(seed));
        assertEquals(1, root.ignored.get(seed));
    }

    @Test void cannotInventMissingStockOrBorrowFromAnotherTransaction() {
        var root = new State(null, Map.of(seed, 1L)); root.ignore(seed);
        var other = new State(null, Map.of(seed, 64L)); other.ignore(seed);
        try (var transaction = new AelisIgnoredSeedScope(root); var lease = AelisIgnoredSeedScope.lease(root);
                var foreign = AelisIgnoredSeedScope.lease(other)) {
            assertEquals(0, foreign.expose(seed, 1));
            assertEquals(1, lease.expose(seed, 2));
            assertEquals(0, lease.expose(seed, 1));
        }
        assertEquals(0, root.extract(seed, 1, Actionable.SIMULATE));
        assertEquals(0, other.extract(seed, 1, Actionable.SIMULATE));
    }

    /** Uses AE2's real cache/diff implementation; only replaces the absent unit-test Mixins. */
    private static final class State extends CraftingSimulationState implements AelisIgnoredSeedInventory,
            CraftingSimulationStateLongSafetyAccessor, AelisChildSimulationStateAccessor {
        final State parent;
        final Map<AEKey, Long> stock;
        final Map<AEKey, Long> ignored = new HashMap<>();
        State(State parent, Map<AEKey, Long> stock) { this.parent = parent; this.stock = stock; }
        @Override protected long simulateExtractParent(AEKey key, long amount) { return parent == null ? Math.min(amount, stock.getOrDefault(key, 0L)) : parent.extract(key, amount, Actionable.SIMULATE); }
        @Override protected Iterable<AEKey> findFuzzyParent(AEKey key) { return parent == null ? stock.keySet() : parent.findFuzzyTemplates(key); }
        @Override public void ignore(AEKey key) { ignored.put(key, extract(key, Long.MAX_VALUE, Actionable.SIMULATE)); super.ignore(key); }
        public Map<AEKey, Long> appliedenhancements$ignoredSeeds() { return ignored; }
        public CraftingSimulationState appliedenhancements$getParent() { return parent; }
        public KeyCounter appliedenhancements$getUnmodifiedCache() { return field("unmodifiedCache"); }
        public KeyCounter appliedenhancements$getModifiableCache() { return field("modifiableCache"); }
        public KeyCounter appliedenhancements$getRequiredExtract() { return field("requiredExtract"); }
        public Map<IPatternDetails, Long> appliedenhancements$getCrafts() { return field("crafts"); }
        @SuppressWarnings("unchecked") private <T> T field(String name) {
            try { var field = CraftingSimulationState.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(this); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }
}
