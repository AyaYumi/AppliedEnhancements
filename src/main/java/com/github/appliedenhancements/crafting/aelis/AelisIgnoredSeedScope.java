package com.github.appliedenhancements.crafting.aelis;

import appeng.api.stacks.AEKey;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.mixin.AelisChildSimulationStateAccessor;
import com.appliedenhancements.mixin.CraftingSimulationStateLongSafetyAccessor;
import com.github.appliedenhancements.integration.ae2.AelisIgnoredSeedInventory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Makes only a solver-proven missing startup seed visible, with nested rollback. */
final class AelisIgnoredSeedScope implements AutoCloseable {
    private static final ThreadLocal<AelisIgnoredSeedScope> CURRENT = new ThreadLocal<>();
    private final AelisIgnoredSeedScope previous = CURRENT.get();
    private final CraftingSimulationState root;
    private final Map<CraftingSimulationState, Map<AEKey, Snapshot>> original = new IdentityHashMap<>();
    private final Map<CraftingSimulationState, Map<AEKey, Long>> borrowed = new IdentityHashMap<>();
    private boolean committed;

    AelisIgnoredSeedScope(CraftingSimulationState root) {
        this.root = root;
        CURRENT.set(this);
    }

    static Lease lease(CraftingSimulationState state) {
        return new Lease(CURRENT.get(), state);
    }

    void commit() { committed = true; }

    @Override
    public void close() {
        if (!committed) restore(original);
        else borrowed.forEach((state, keys) -> {
            if (state instanceof AelisIgnoredSeedInventory seeds) keys.keySet().forEach(seeds.appliedenhancements$ignoredSeeds()::remove);
        });
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    private static void capture(Map<CraftingSimulationState, Map<AEKey, Snapshot>> target,
            CraftingSimulationState state, AEKey key) {
        target.computeIfAbsent(state, ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> Snapshot.of(state, key));
    }

    private static void restore(Map<CraftingSimulationState, Map<AEKey, Snapshot>> snapshots) {
        snapshots.forEach((state, keys) -> keys.forEach((key, snapshot) -> snapshot.restore(state, key)));
    }

    private record Snapshot(long unmodified, long modifiable, long required) {
        static Snapshot of(CraftingSimulationState state, AEKey key) {
            var fields = (CraftingSimulationStateLongSafetyAccessor) state;
            return new Snapshot(fields.appliedenhancements$getUnmodifiedCache().get(key),
                    fields.appliedenhancements$getModifiableCache().get(key),
                    fields.appliedenhancements$getRequiredExtract().get(key));
        }
        void restore(CraftingSimulationState state, AEKey key) {
            var fields = (CraftingSimulationStateLongSafetyAccessor) state;
            fields.appliedenhancements$getUnmodifiedCache().set(key, unmodified);
            fields.appliedenhancements$getModifiableCache().set(key, modifiable);
            fields.appliedenhancements$getRequiredExtract().set(key, required);
        }
    }

    static final class Lease implements AutoCloseable {
        private final AelisIgnoredSeedScope scope;
        private final CraftingSimulationState state;
        private final Map<CraftingSimulationState, Map<AEKey, Snapshot>> before = new IdentityHashMap<>();
        private final Map<CraftingSimulationState, Map<AEKey, Long>> oldBorrowed = new IdentityHashMap<>();
        private boolean committed;

        private Lease(AelisIgnoredSeedScope scope, CraftingSimulationState state) {
            this.scope = scope;
            this.state = state;
        }

        long expose(AEKey key, long needed) {
            if (scope == null || needed <= 0) return 0;
            var chain = new ArrayList<CraftingSimulationState>();
            CraftingSimulationState current = state;
            while (current != null) {
                chain.add(current);
                if (current == scope.root) break;
                current = current instanceof AelisChildSimulationStateAccessor child
                        && child.appliedenhancements$getParent() instanceof CraftingSimulationState parent ? parent : null;
            }
            if (current != scope.root) return 0;
            // Only the current API transaction may expose its ignored root stock.
            for (int index = 0; index < chain.size(); index++) {
                var owner = chain.get(index);
                if (!(owner instanceof AelisIgnoredSeedInventory seeds)) continue;
                long hidden = seeds.appliedenhancements$ignoredSeeds().getOrDefault(key, 0L);
                long lent = scope.borrowed.getOrDefault(owner, Map.of()).getOrDefault(key, 0L);
                long amount = Math.min(needed, hidden - lent);
                if (amount <= 0) continue;
                var affected = chain.subList(0, index + 1);
                for (var target : affected) {
                    if (!(target instanceof CraftingSimulationStateLongSafetyAccessor fields)
                            || fields.appliedenhancements$getUnmodifiedCache().get(key) > Long.MAX_VALUE - amount
                            || fields.appliedenhancements$getModifiableCache().get(key) > Long.MAX_VALUE - amount) return 0;
                }
                for (var target : affected) {
                    capture(before, target, key);
                    capture(scope.original, target, key);
                    var fields = (CraftingSimulationStateLongSafetyAccessor) target;
                    fields.appliedenhancements$getUnmodifiedCache().add(key, amount);
                    fields.appliedenhancements$getModifiableCache().add(key, amount);
                }
                oldBorrowed.computeIfAbsent(owner, ignored -> new HashMap<>()).putIfAbsent(key, lent);
                scope.borrowed.computeIfAbsent(owner, ignored -> new HashMap<>()).put(key, lent + amount);
                return amount;
            }
            return 0;
        }

        void commit() { committed = true; }

        @Override public void close() {
            if (committed || scope == null) return;
            restore(before);
            oldBorrowed.forEach((owner, keys) -> keys.forEach((key, amount) -> {
                var values = scope.borrowed.get(owner);
                if (amount == 0) values.remove(key); else values.put(key, amount);
            }));
        }
    }
}
