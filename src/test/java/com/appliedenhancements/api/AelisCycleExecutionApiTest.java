package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.crafting.CraftingPlan;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCycleExecutionPlanCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class AelisCycleExecutionApiTest {
    @Test
    void preparingAnOrdinaryPlanKeepsItsIdentityAndData() {
        var ordinary = new TestPlan(null);
        assertSame(ordinary, AelisCycleExecutionApi.preparePlan(ordinary));
        assertTrue(AelisCycleExecutionApi.getCyclicCraftAmounts(ordinary).isEmpty());
    }

    @Test
    void cyclicQuantitySnapshotsSurvivePreparationAndCannotMutateThePlan() {
        var seed = new TestAEKey("public_amount_seed");
        var cycle = new AelisCycleExecutionPlan(List.of(new AelisCycleExecutionPlan.Step(
                new TestAEKey("public_amount_pattern"), 2, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed));
        var amounts = new HashMap<AEKey, Long>();
        amounts.put(seed, 18L);
        var plan = new TestPlan(cycle, amounts);
        var snapshot = AelisCycleExecutionApi.getCyclicCraftAmounts(plan);
        var prepared = AelisCycleExecutionApi.preparePlan(plan);
        amounts.put(seed, 99L);
        assertEquals(Map.of(seed, 18L), snapshot);
        assertEquals(snapshot, AelisCycleExecutionApi.getCyclicCraftAmounts(prepared));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put(seed, 1L));
        assertEquals(cycle, AelisCycleExecutionApi.getPlan(prepared).orElseThrow());
    }

    @Test
    void publicGuardAndBatchCounterPreserveTheSameExtractionAttempt() {
        var seed = new TestAEKey("public_guard_seed");
        var pattern = new TestAEKey("public_guard_pattern");
        var next = new TestAEKey("public_guard_next");
        var cycle = new AelisCycleExecutionPlan(List.of(
                new AelisCycleExecutionPlan.Step(pattern, 3, Map.of(seed, 2L), Set.of(seed)),
                new AelisCycleExecutionPlan.Step(next, 1, Map.of(seed, 1L), Set.of())),
                Map.of(seed, 1L), Set.of(seed), AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        var runtime = new AelisCycleRuntimeController(cycle);
        var inventory = new ListCraftingInventory(ignored -> {});
        inventory.insert(seed, 100, Actionable.MODULATE);
        assertSame(inventory, AelisCycleExecutionApi.guardInputs(null, pattern, inventory));
        assertNull(AelisCycleExecutionApi.guardInputs(runtime, next, inventory));
        var guarded = AelisCycleExecutionApi.guardInputs(runtime, pattern, inventory);
        assertEquals(6, guarded.extract(seed, 100, Actionable.MODULATE));
        assertEquals(0, guarded.extract(seed, 100, Actionable.SIMULATE));
        var holder = new KeyCounter();
        holder.add(seed, 6);
        assertEquals(3, AelisCycleExecutionApi.dispatchedCrafts(runtime, pattern, new KeyCounter[] {holder}));
        assertEquals(3, runtime.remainingCrafts());
        guarded.insert(seed, 6, Actionable.MODULATE);
        assertEquals(6, guarded.extract(seed, 100, Actionable.SIMULATE));
        assertEquals(100, inventory.list.get(seed));
    }

    @Test
    void copyingToCustomPlanPreservesDelegateDataAndCycleCapability() {
        var seed = new TestAEKey("api_seed");
        var cycle = new AelisCycleExecutionPlan(List.of(new AelisCycleExecutionPlan.Step(
                new TestAEKey("api_pattern"), 2, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed));
        var output = new GenericStack(seed, 16);
        var used = new KeyCounter();
        used.add(seed, 1);
        var target = new PlainPlan(output, 321, true, true, used,
                new KeyCounter(), new KeyCounter(), Map.of());

        var wrapped = AelisCycleExecutionApi.copyMetadata(new TestPlan(cycle), target);

        assertNotSame(target, wrapped);
        assertSame(output, wrapped.finalOutput());
        assertSame(used, wrapped.usedItems());
        assertEquals(321, wrapped.bytes());
        assertTrue(wrapped.simulation());
        assertTrue(wrapped.multiplePaths());
        assertEquals(cycle, AelisCycleExecutionApi.getPlan(wrapped).orElseThrow());
        assertFalse(AelisCycleExecutionApi.requiresCycleAwareCpu(target));
    }

    @Test
    void metadataSurvivesMultipleCustomPlanReplacements() {
        var seed = new TestAEKey("seed");
        var cycle = new AelisCycleExecutionPlan(List.of(new AelisCycleExecutionPlan.Step(
                new TestAEKey("pattern"), 1, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed));
        var target = new TestPlan(null);
        var first = AelisCycleExecutionApi.copyMetadata(new TestPlan(cycle), target);
        var second = AelisCycleExecutionApi.copyMetadata(first, target);
        assertEquals(cycle, AelisCycleExecutionApi.getPlan(second).orElseThrow());
        assertFalse(AelisCycleExecutionApi.requiresCycleAwareCpu(target));
    }

    @Test
    void copyingOrdinaryMetadataDoesNotLeaveAStaleCycle() {
        var seed = new TestAEKey("seed");
        var cycle = new AelisCycleExecutionPlan(List.of(new AelisCycleExecutionPlan.Step(
                new TestAEKey("pattern"), 1, Map.of(seed, 1L), Set.of(seed))),
                Map.of(seed, 1L), Set.of(seed));
        var wrapped = AelisCycleExecutionApi.copyMetadata(new TestPlan(cycle), new TestPlan(null));
        var cleared = AelisCycleExecutionApi.copyMetadata(new TestPlan(null), wrapped);
        assertFalse(AelisCycleExecutionApi.requiresCycleAwareCpu(cleared));
    }

    @Test
    void exactOutputTotalsAloneDoNotMakeRepresentableTasksPreviewOnly() {
        var outputKey = new TestAEKey("projected_output");
        var exact = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        var source = new TestPlan(null, Map.of(), Map.of(outputKey, exact));
        var nativePlan = new CraftingPlan(
                new GenericStack(outputKey, 1), 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());

        var projected = AelisCycleExecutionApi.copyMetadata(source, nativePlan);

        assertFalse(projected.simulation());
        assertEquals(Map.of(outputKey, exact),
                ((AelisBigIntegerCraftAmountsCarrier) projected)
                        .appliedenhancements$getBigIntegerCraftAmounts());
    }

    private record PlainPlan(GenericStack finalOutput, long bytes, boolean simulation,
            boolean multiplePaths, KeyCounter usedItems, KeyCounter emittedItems,
            KeyCounter missingItems, Map<IPatternDetails, Long> patternTimes) implements ICraftingPlan {}

    @Test void projectedPatternCountsDoNotVetoSubmissionAndMetadataSurvivesCopy() {
        var key = new TestAEKey("projected_pattern");
        var exact = BigInteger.TEN.pow(21);
        var nativePlan = new CraftingPlan(new GenericStack(key, 1), 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var source = AelisCycleExecutionApi.copyMetadata(new TestPlan(null, Map.of(), Map.of(key, exact)), nativePlan);
        var metadata = (AelisBigIntegerCraftAmountsCarrier) source;
        metadata.appliedenhancements$setPreviewOnly(true);
        metadata.appliedenhancements$setBigIntegerMissingAmounts(Map.of(key, exact.multiply(BigInteger.TEN)));
        metadata.appliedenhancements$setBigIntegerStoredAmounts(Map.of(key, exact.multiply(BigInteger.valueOf(1024))));
        metadata.appliedenhancements$setBigIntegerInfiniteAmounts(Map.of(key, exact));
        var pattern = (IPatternDetails) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IPatternDetails.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
        metadata.appliedenhancements$setBigIntegerPatternTimes(Map.of(pattern, exact));
        metadata.appliedenhancements$setBigIntegerBytes(exact.add(BigInteger.ONE));
        var copied = AelisCycleExecutionApi.copyMetadata(source, nativePlan);
        assertFalse(copied.simulation());
        assertEquals(exact.add(BigInteger.ONE),
                ((AelisBigIntegerCraftAmountsCarrier) copied).appliedenhancements$getBigIntegerBytes());
        assertTrue(((AelisBigIntegerCraftAmountsCarrier) copied).appliedenhancements$isPreviewOnly());
        assertEquals(metadata.appliedenhancements$getBigIntegerMissingAmounts(),
                ((AelisBigIntegerCraftAmountsCarrier) copied).appliedenhancements$getBigIntegerMissingAmounts());
        assertEquals(metadata.appliedenhancements$getBigIntegerStoredAmounts(),
                ((AelisBigIntegerCraftAmountsCarrier) copied).appliedenhancements$getBigIntegerStoredAmounts());
        assertEquals(metadata.appliedenhancements$getBigIntegerInfiniteAmounts(),
                ((AelisBigIntegerCraftAmountsCarrier) copied).appliedenhancements$getBigIntegerInfiniteAmounts());
        assertEquals(Map.of(pattern, exact),
                ((AelisBigIntegerCraftAmountsCarrier) copied).appliedenhancements$getBigIntegerPatternTimes());
        var missingPlan = new PlainPlan(nativePlan.finalOutput(), 1, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        assertTrue(AelisCycleExecutionApi.copyMetadata(copied, missingPlan).simulation(),
                "An actual simulation must remain a simulation");
    }

    @Test
    void exposesPlanMetadataAndRequiresExplicitCpuCapability() {
        var key = new TestAEKey("seed");
        var cyclePlan = new AelisCycleExecutionPlan(
                List.of(new AelisCycleExecutionPlan.Step(
                        new TestAEKey("pattern"), 1,
                        Map.of(key, 1L), Set.of(key))),
                Map.of(key, 1L), Set.of(key),
                AelisCycleSeedPolicy.PRESERVE_MINIMUM);
        ICraftingPlan plan = new TestPlan(cyclePlan);

        assertEquals(cyclePlan, AelisCycleExecutionApi.getPlan(plan).orElseThrow());
        assertTrue(AelisCycleExecutionApi.requiresCycleAwareCpu(plan));
        assertTrue(AelisCycleExecutionApi.supports(new AwareCpu()));
        assertFalse(AelisCycleExecutionApi.supports(new PlainCpu()));
    }

    private record TestPlan(AelisCycleExecutionPlan cyclePlan,
            Map<AEKey, Long> amounts, Map<AEKey, BigInteger> exactAmounts)
            implements ICraftingPlan, AelisCycleExecutionPlanCarrier,
            AelisCyclicCraftAmountsCarrier, AelisBigIntegerCraftAmountsCarrier {
        TestPlan(AelisCycleExecutionPlan cyclePlan) {
            this(cyclePlan, Map.of(), Map.of());
        }

        TestPlan(AelisCycleExecutionPlan cyclePlan, Map<AEKey, Long> amounts) {
            this(cyclePlan, amounts, Map.of());
        }

        @Override public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() { return amounts; }
        @Override public void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> values) {
            throw new UnsupportedOperationException();
        }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() {
            return exactAmounts;
        }
        @Override public void appliedenhancements$setBigIntegerCraftAmounts(
                Map<AEKey, BigInteger> values) {
            throw new UnsupportedOperationException();
        }
        @Override
        public AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan() {
            return cyclePlan;
        }

        @Override
        public void appliedenhancements$setCycleExecutionPlan(
                AelisCycleExecutionPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GenericStack finalOutput() {
            return null;
        }

        @Override
        public long bytes() {
            return 0;
        }

        @Override
        public boolean simulation() {
            return false;
        }

        @Override
        public boolean multiplePaths() {
            return false;
        }

        @Override
        public KeyCounter usedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter emittedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter missingItems() {
            return new KeyCounter();
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return Map.of();
        }
    }

    private static class PlainCpu implements ICraftingCPU {
        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public CraftingJobStatus getJobStatus() {
            return null;
        }

        @Override
        public void cancelJob() {
        }

        @Override
        public long getAvailableStorage() {
            return Long.MAX_VALUE;
        }

        @Override
        public int getCoProcessors() {
            return 0;
        }

        @Override
        public Component getName() {
            return null;
        }

        @Override
        public CpuSelectionMode getSelectionMode() {
            return CpuSelectionMode.ANY;
        }
    }

    private static final class AwareCpu extends PlainCpu
            implements AelisCycleAwareCpu {
    }
}
