package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import com.appliedenhancements.api.AelisExactCraftingPlanApi;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AelisForcedPlanTest {
    private final AEKey input = new TestAEKey("input");
    private final AEKey output = new TestAEKey("output");

    @Test void missingInputsBecomeAwaitedInputsWithoutCreatingStoredInventory() {
        var source = plan(true);
        source.usedItems().set(input, 4);
        source.emittedItems().set(input, 2);
        source.missingItems().set(input, 8);
        var forced = AelisForcedPlan.prepare(source);
        assertFalse(forced.simulation());
        assertEquals(10, forced.emittedItems().get(input));
        assertEquals(4, forced.usedItems().get(input));
        assertTrue(forced.missingItems().isEmpty());
        assertSame(source.finalOutput(), forced.finalOutput());
        assertEquals(source.bytes(), forced.bytes());
        assertTrue(forced.multiplePaths());
        assertTrue(source.simulation());
        assertEquals(8, source.missingItems().get(input));
        assertEquals(2, source.emittedItems().get(input));
        assertEquals(4, source.usedItems().get(input));
    }

    @Test void readyPlanKeepsItsIdentity() {
        var ready = plan(false);
        assertSame(ready, AelisForcedPlan.prepare(ready));
    }

    @Test void exactMissingOverflowFailsWithoutMutatingTheOriginal() {
        var source = AelisCycleExecutionApi.copyMetadata(plan(true), plan(true));
        source.emittedItems().set(input, 2);
        source.missingItems().set(input, Long.MAX_VALUE);
        var exact = (AelisBigIntegerCraftAmountsCarrier) source;
        var amount = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        exact.appliedenhancements$setBigIntegerMissingAmounts(Map.of(input, amount));
        assertThrows(ArithmeticException.class, () -> AelisForcedPlan.prepare(source));
        assertEquals(2, source.emittedItems().get(input));
        assertEquals(amount, exact.appliedenhancements$getBigIntegerMissingAmounts().get(input));
        assertTrue(source.simulation());
    }

    @Test void combinedAwaitedAmountOverflowFailsWithoutMutatingTheOriginal() {
        var source = plan(true);
        source.emittedItems().set(input, Long.MAX_VALUE);
        source.missingItems().set(input, 1);
        assertThrows(ArithmeticException.class, () -> AelisForcedPlan.prepare(source));
        assertEquals(Long.MAX_VALUE, source.emittedItems().get(input));
        assertEquals(1, source.missingItems().get(input));
    }

    @Test void exactTasksFinalAmountAndAelisMetadataSurviveForcedSubmission() {
        var task = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
        var huge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN);
        var source = AelisExactCraftingPlanApi.attachExecutionMetadata(plan(true), huge, Map.of(task, huge), Map.of());
        ((AelisCalculationPathCarrier) source).molecularmanipulator$setCalculationPath(AelisCalculationPath.AELIS);
        ((AelisCyclicCraftAmountsCarrier) source).appliedenhancements$setCyclicCraftAmounts(Map.of(output, 7L));
        source.missingItems().set(input, 3);
        var forced = AelisForcedPlan.prepare(source);
        assertEquals(huge, AelisExactCraftingPlanApi.getFinalOutputAmount(forced));
        assertEquals(Map.of(task, huge), AelisExactCraftingPlanApi.getPatternTimes(forced));
        assertEquals(Map.of(output, 7L), AelisCycleExecutionApi.getCyclicCraftAmounts(forced));
        assertEquals(AelisCalculationPath.AELIS,
                ((AelisCalculationPathCarrier) forced).molecularmanipulator$getCalculationPath());
        assertEquals(3, forced.emittedItems().get(input));
        assertTrue(((AelisBigIntegerCraftAmountsCarrier) forced).appliedenhancements$getBigIntegerMissingAmounts().isEmpty());
    }

    private ICraftingPlan plan(boolean simulation) {
        return new TestPlan(new GenericStack(output, 1), 128, simulation, true,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    private record TestPlan(GenericStack finalOutput, long bytes, boolean simulation, boolean multiplePaths,
            KeyCounter usedItems, KeyCounter emittedItems, KeyCounter missingItems,
            Map<IPatternDetails, Long> patternTimes) implements ICraftingPlan {}
}
