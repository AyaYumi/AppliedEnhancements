package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AelisPublicMetadataTest {
    private static final AEKey OUTPUT = new TestAEKey("output");
    private static final AEKey INPUT = new TestAEKey("input");
    private static final BigInteger HUGE = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

    @Test void requestPreservesExactAmountAndRejectsUnsupportedSearch() {
        assertEquals(HUGE, AelisCraftingRequest.of(OUTPUT, HUGE).amount());
        assertEquals(BigInteger.ONE, AelisCraftingRequest.of(OUTPUT, 1L).amount());
        assertThrows(IllegalArgumentException.class, () -> AelisCraftingRequest.of(OUTPUT, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AelisCraftingRequest.of(OUTPUT, BigInteger.TEN.pow(256)));
        assertThrows(IllegalArgumentException.class,
                () -> new AelisCraftingRequest(OUTPUT, HUGE, CalculationStrategy.CRAFT_LESS));
    }

    @Test void categorizesExistingPlannerFallbackReasons() {
        assertEquals(AelisFallbackReason.TIME_LIMIT, AelisFallbackReason.fromDetail("compile_time_budget"));
        assertEquals(AelisFallbackReason.NODE_LIMIT, AelisFallbackReason.fromDetail("node_limit"));
        assertEquals(AelisFallbackReason.INVALID_REQUEST, AelisFallbackReason.fromDetail("invalid_request_amount"));
        assertEquals(AelisFallbackReason.COMPATIBILITY, AelisFallbackReason.fromDetail("variable_output_candidates"));
        assertEquals(AelisFallbackReason.ORDERED_CHOICE, AelisFallbackReason.fromDetail("multi_occurrence_ordered_choices"));
        assertEquals(AelisFallbackReason.OTHER, AelisFallbackReason.fromDetail("future_unknown_reason"));
    }

    @Test void ordinaryPlanIncludesMaterialsAndIsAnImmutableSnapshot() {
        var plan = new Plan();
        var pattern = pattern(2);
        plan.tasks.put(pattern, 3L);
        plan.missing.add(INPUT, 7);
        plan.used.add(INPUT, 5);
        var data = AelisExactCraftingPlanApi.read(plan);
        assertEquals(Map.of(OUTPUT, BigInteger.valueOf(6)), data.craftedAmounts());
        assertEquals(Map.of(INPUT, BigInteger.valueOf(7)), data.missingAmounts());
        assertEquals(Map.of(INPUT, BigInteger.valueOf(5)), data.storedAmounts());
        assertEquals(AelisPlanPath.EXTERNAL, data.path());
        assertFalse(data.executionRequirement().requiresExactMetadata());
        plan.tasks.clear();
        plan.used.clear();
        assertEquals(BigInteger.valueOf(3), data.patternTimes().get(pattern));
        assertEquals(BigInteger.valueOf(5), data.storedAmounts().get(INPUT));
        assertThrows(UnsupportedOperationException.class, () -> data.patternTimes().clear());
    }

    @Test void completeExactTasksReplaceStaleProjectionAndSparseMaterialsOverride() {
        var plan = new ExactPlan();
        var original = pattern(2);
        plan.tasks.put(pattern(100), 10L);
        plan.exactTasks = Map.of(original, HUGE);
        plan.missing.add(INPUT, Long.MAX_VALUE);
        plan.exactMissing = Map.of(INPUT, HUGE);
        var data = AelisExactCraftingPlanApi.read(plan);
        assertEquals(Map.of(original, HUGE), data.patternTimes());
        assertEquals(HUGE.multiply(BigInteger.TWO), data.craftedAmounts().get(OUTPUT));
        assertEquals(HUGE, data.missingAmounts().get(INPUT));
        assertEquals(AelisPlanPath.AELIS, data.path());
        assertTrue(data.executionRequirement().patternTimes());
        assertTrue(data.executionRequirement().craftedAmounts());
        assertTrue(data.executionRequirement().missingAmounts());
    }

    @Test void safeIndividualCountsCanOverflowAnAggregatedOutput() {
        var plan = new Plan();
        plan.tasks.put(pattern(1), Long.MAX_VALUE);
        plan.tasks.put(pattern(1), 1L);
        var requirement = AelisExactCraftingPlanApi.executionRequirement(plan);
        assertFalse(requirement.patternTimes());
        assertTrue(requirement.craftedAmounts());
        assertTrue(requirement.requiresExactExecution());
    }

    @Test void accountingOnlyOverflowDoesNotClaimCpuExecutionCapability() {
        var plan = new ExactPlan();
        plan.exactMissing = Map.of(INPUT, HUGE);
        plan.exactBytes = HUGE;
        var requirement = AelisExactCraftingPlanApi.executionRequirement(plan);
        assertTrue(requirement.bytes());
        assertTrue(requirement.missingAmounts());
        assertFalse(requirement.requiresExactExecution());
        assertTrue(requirement.requiresExactMetadata());
    }

    @Test void reportsProjectionFlagWithoutCallingItPreviewOnly() {
        var plan = new ExactPlan() {
            @Override public boolean appliedenhancements$isPreviewOnly() { return true; }
        };
        assertTrue(AelisExactCraftingPlanApi.read(plan).projectionSaturated());
        assertTrue(AelisExactCraftingPlanApi.executionRequirement(plan).requiresExactExecution());
    }

    @Test void nullSourceOnExternalCarrierDoesNotClaimNativeOrigin() {
        var plan = new ExactPlan() {
            @Override public AelisCalculationPath molecularmanipulator$getCalculationPath() { return null; }
        };
        assertEquals(AelisPlanPath.EXTERNAL, AelisExactCraftingPlanApi.read(plan).path());
    }

    @Test void invalidNegativeNativeQuantityCannotBeReportedAsSafe() {
        var plan = new Plan();
        plan.used.set(INPUT, -1);
        assertThrows(IllegalArgumentException.class, () -> AelisExactCraftingPlanApi.read(plan));
    }

    private static IPatternDetails pattern(long output) {
        return (IPatternDetails) Proxy.newProxyInstance(AelisPublicMetadataTest.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getOutputs" -> java.util.List.of(new GenericStack(OUTPUT, output));
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static class Plan implements ICraftingPlan {
        final Map<IPatternDetails, Long> tasks = new HashMap<>();
        final KeyCounter used = new KeyCounter();
        final KeyCounter missing = new KeyCounter();
        @Override public GenericStack finalOutput() { return new GenericStack(OUTPUT, 1); }
        @Override public long bytes() { return 0; }
        @Override public boolean simulation() { return false; }
        @Override public boolean multiplePaths() { return false; }
        @Override public KeyCounter usedItems() { return used; }
        @Override public KeyCounter emittedItems() { return new KeyCounter(); }
        @Override public KeyCounter missingItems() { return missing; }
        @Override public Map<IPatternDetails, Long> patternTimes() { return tasks; }
    }

    private static class ExactPlan extends Plan implements AelisBigIntegerCraftAmountsCarrier, AelisCalculationPathCarrier {
        Map<IPatternDetails, BigInteger> exactTasks = Map.of();
        Map<AEKey, BigInteger> exactMissing = Map.of();
        BigInteger exactBytes;
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() { return Map.of(); }
        @Override public void appliedenhancements$setBigIntegerCraftAmounts(Map<AEKey, BigInteger> values) { throw new UnsupportedOperationException(); }
        @Override public Map<IPatternDetails, BigInteger> appliedenhancements$getBigIntegerPatternTimes() { return exactTasks; }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() { return exactMissing; }
        @Override public BigInteger appliedenhancements$getBigIntegerBytes() { return exactBytes; }
        @Override public AelisCalculationPath molecularmanipulator$getCalculationPath() { return AelisCalculationPath.AELIS; }
        @Override public void molecularmanipulator$setCalculationPath(AelisCalculationPath path) { throw new UnsupportedOperationException(); }
    }
}
