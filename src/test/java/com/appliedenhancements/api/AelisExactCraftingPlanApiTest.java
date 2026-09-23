package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AelisExactCraftingPlanApiTest {
    @Test
    void plainPlansExposeNoExactMetadata() {
        var plan = new PlainPlan();

        assertEquals(Map.of(), AelisExactCraftingPlanApi.getPatternTimes(plan));
        assertEquals(Map.of(), AelisExactCraftingPlanApi.getInfiniteInputAmounts(plan));
        assertFalse(AelisExactCraftingPlanApi.requiresExactExecution(plan));
    }

    @Test
    void exposesExactPatternsAndExplicitInfiniteInputs() {
        var pattern = pattern();
        var key = new TestAEKey("infinite_input");
        var exact = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        var plan = new ExactPlan(Map.of(pattern, exact), Map.of(key, exact));

        assertEquals(Map.of(pattern, exact), AelisExactCraftingPlanApi.getPatternTimes(plan));
        assertEquals(Map.of(key, exact), AelisExactCraftingPlanApi.getInfiniteInputAmounts(plan));
        assertTrue(AelisExactCraftingPlanApi.requiresExactExecution(plan));
    }

    @Test
    void longMaxStillFitsNativeExecution() {
        var plan = new ExactPlan(
                Map.of(pattern(), BigInteger.valueOf(Long.MAX_VALUE)), Map.of());

        assertFalse(AelisExactCraftingPlanApi.requiresExactExecution(plan));
    }

    @Test
    void thirdPartyPlanAttachesAndCopiesExactFinalOutputWithoutOmniTypes() {
        var original = new PlainPlan();
        var amount = new BigInteger("99999999999999999999");
        var pattern = pattern();
        var input = new TestAEKey("infinite");
        var attached = AelisExactCraftingPlanApi.attachExecutionMetadata(original, amount,
                Map.of(pattern, amount), Map.of(input, amount));
        var copy = AelisCycleExecutionApi.copyMetadata(attached, new PlainPlan());
        assertEquals(amount, AelisExactCraftingPlanApi.getFinalOutputAmount(copy));
        assertEquals(Map.of(pattern, amount), AelisExactCraftingPlanApi.getPatternTimes(copy));
        assertEquals(Map.of(input, amount), AelisExactCraftingPlanApi.getInfiniteInputAmounts(copy));
        assertTrue(AelisExactCraftingPlanApi.requiresExactExecution(copy));
    }

    private static IPatternDetails pattern() {
        return (IPatternDetails) Proxy.newProxyInstance(
                AelisExactCraftingPlanApiTest.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static class PlainPlan implements ICraftingPlan {
        @Override public GenericStack finalOutput() { return new GenericStack(new TestAEKey("output"), 1); }
        @Override public long bytes() { return 0; }
        @Override public boolean simulation() { return false; }
        @Override public boolean multiplePaths() { return false; }
        @Override public KeyCounter usedItems() { return new KeyCounter(); }
        @Override public KeyCounter emittedItems() { return new KeyCounter(); }
        @Override public KeyCounter missingItems() { return new KeyCounter(); }
        @Override public Map<IPatternDetails, Long> patternTimes() { return Map.of(); }
    }

    @Test
    void completeExactLedgerDoesNotMergeStaleRewrittenProjection() {
        var original = pattern();
        var scaled = pattern();
        var amount = new BigInteger("99999999999999999999");
        var plan = new ExactPlan(Map.of(original, amount), Map.of()) {
            @Override public Map<IPatternDetails, Long> patternTimes() { return Map.of(scaled, 1L); }
        };
        assertEquals(Map.of(original, amount), AelisExactCraftingPlanApi.getPatternTimes(plan));
    }

    @Test
    void nonCyclicSubmissionRewritePreservesExactMetadata() {
        var task = pattern();
        var amount = new BigInteger("99999999999999999999");
        var input = new TestAEKey("rewrite_input");
        var plan = AelisExactCraftingPlanApi.attachExecutionMetadata(new PlainPlan(), amount,
                Map.of(task, amount), Map.of(input, amount));
        var rewritten = com.appliedenhancements.runtime.AelisCraftingPlanRewrite.rewriteOrdinaryPatterns(
                plan, ignored -> new PlainPlan() {
                    @Override public Map<IPatternDetails, Long> patternTimes() { return Map.of(task, Long.MAX_VALUE); }
                });
        assertEquals(amount, AelisExactCraftingPlanApi.getFinalOutputAmount(rewritten));
        assertEquals(Map.of(task, amount), AelisExactCraftingPlanApi.getPatternTimes(rewritten));
        assertEquals(Map.of(input, amount), AelisExactCraftingPlanApi.getInfiniteInputAmounts(rewritten));
        assertTrue(AelisExactCraftingPlanApi.requiresExactExecution(rewritten));
    }

    private static class ExactPlan extends PlainPlan
            implements AelisBigIntegerCraftAmountsCarrier {
        private final Map<IPatternDetails, BigInteger> patternTimes;
        private final Map<AEKey, BigInteger> infiniteInputs;

        private ExactPlan(Map<IPatternDetails, BigInteger> patternTimes,
                Map<AEKey, BigInteger> infiniteInputs) {
            this.patternTimes = patternTimes;
            this.infiniteInputs = infiniteInputs;
        }

        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() {
            return Map.of();
        }
        @Override public void appliedenhancements$setBigIntegerCraftAmounts(Map<AEKey, BigInteger> amounts) {
            throw new UnsupportedOperationException();
        }
        @Override public Map<IPatternDetails, BigInteger> appliedenhancements$getBigIntegerPatternTimes() {
            return patternTimes;
        }
        @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerInfiniteAmounts() {
            return infiniteInputs;
        }
    }
}
