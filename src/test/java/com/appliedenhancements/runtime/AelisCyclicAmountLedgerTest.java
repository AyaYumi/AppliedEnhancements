package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.mixin.CraftingSimulationStateCyclicCraftingMixin;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftingTracker;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

class AelisCyclicAmountLedgerTest {
    @Test void cyclicOutputTotalsRemainExactAndSeparateFromOrdinaryCrafting() {
        var key = new TestAEKey("shared_output");
        var pattern = pattern(key);
        var tracker = new CraftingSimulationStateCyclicCraftingMixin() {};
        var huge = BigInteger.TEN.pow(25);
        tracker.appliedenhancements$recordBigIntegerCrafting(pattern, huge);
        tracker.appliedenhancements$recordCyclicCrafting(pattern, huge);
        tracker.appliedenhancements$recordBigIntegerCrafting(pattern, 7);
        var snapshot = tracker.appliedenhancements$getBigIntegerCyclicCraftAmounts();
        tracker.appliedenhancements$recordCyclicCrafting(pattern, 3);

        assertEquals(huge.multiply(BigInteger.valueOf(3)), snapshot.get(key));
        assertEquals(huge.add(BigInteger.valueOf(3)).multiply(BigInteger.valueOf(3)),
                tracker.appliedenhancements$getBigIntegerCyclicCraftAmounts().get(key));
        assertEquals(huge.add(BigInteger.valueOf(7)).multiply(BigInteger.valueOf(3)),
                tracker.appliedenhancements$getBigIntegerCraftAmounts().get(key));
        assertEquals(Long.MAX_VALUE, tracker.appliedenhancements$getCyclicCraftAmounts().get(key));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test void transactionReturnHookTransfersExactCyclicAmountsOnce() throws Exception {
        var key = new TestAEKey("transaction_output");
        var pattern = pattern(key);
        var child = new CraftingSimulationStateCyclicCraftingMixin() {};
        var parent = new State();
        parent.appliedenhancements$recordCyclicCrafting(pattern, 2);
        child.appliedenhancements$recordCyclicCrafting(pattern, BigInteger.TEN.pow(25));
        var hook = CraftingSimulationStateCyclicCraftingMixin.class.getDeclaredMethod(
                "appliedenhancements$mergeCyclicCraftingIntoParent", CraftingSimulationState.class, CallbackInfo.class);
        hook.setAccessible(true);
        try (var scope = CraftingPlannerIntervention.openExplicit()) {
            hook.invoke(child, parent, new CallbackInfo("applyDiff", false));
        }
        assertEquals(BigInteger.TEN.pow(25).add(BigInteger.TWO).multiply(BigInteger.valueOf(3)),
                parent.appliedenhancements$getBigIntegerCyclicCraftAmounts().get(key));
        assertEquals(Long.MAX_VALUE, parent.appliedenhancements$getCyclicCraftAmounts().get(key));
    }

    private static IPatternDetails pattern(AEKey key) {
        return new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return null; }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(key, 3)); }
        };
    }

    private static final class State extends CraftingSimulationState implements AelisCyclicCraftingTracker {
        private final CraftingSimulationStateCyclicCraftingMixin ledger = new CraftingSimulationStateCyclicCraftingMixin() {};
        @Override protected long simulateExtractParent(AEKey key, long amount) { return 0; }
        @Override protected Iterable<AEKey> findFuzzyParent(AEKey key) { return List.of(); }
        @Override public void appliedenhancements$recordCyclicCrafting(IPatternDetails pattern, long times) { ledger.appliedenhancements$recordCyclicCrafting(pattern, times); }
        @Override public void appliedenhancements$recordCyclicCrafting(IPatternDetails pattern, BigInteger times) { ledger.appliedenhancements$recordCyclicCrafting(pattern, times); }
        @Override public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() { return ledger.appliedenhancements$getCyclicCraftAmounts(); }
        @Override public void appliedenhancements$mergeCyclicCraftAmounts(Map<AEKey, Long> amounts) { ledger.appliedenhancements$mergeCyclicCraftAmounts(amounts); }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCyclicCraftAmounts() { return ledger.appliedenhancements$getBigIntegerCyclicCraftAmounts(); }
        @Override public void appliedenhancements$mergeBigIntegerCyclicCraftAmounts(Map<AEKey, BigInteger> amounts) { ledger.appliedenhancements$mergeBigIntegerCyclicCraftAmounts(amounts); }
        @Override public AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan() { return ledger.appliedenhancements$getCycleExecutionPlan(); }
        @Override public void appliedenhancements$recordCycleExecutionPlan(AelisCycleExecutionPlan plan) { ledger.appliedenhancements$recordCycleExecutionPlan(plan); }
    }
}
