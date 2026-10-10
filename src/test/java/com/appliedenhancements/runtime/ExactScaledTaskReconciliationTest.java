package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactScaledTaskReconciliationTest {
    private final IPatternDetails original = pattern();
    private final IPatternDetails batch = pattern();
    private final IPatternDetails smallerBatch = pattern();
    private final BigInteger total = new BigInteger("99999999999999999999");

    private Function<IPatternDetails, ExactScaledTaskReconciliation.ScaledTask> resolver(long scale) {
        return p -> p == batch ? new ExactScaledTaskReconciliation.ScaledTask(original, scale)
                : p == smallerBatch ? new ExactScaledTaskReconciliation.ScaledTask(original, 3) : null;
    }

    @Test void repartitionsBeyondLongWithoutRoundingOrDuplicateWork() {
        long scale = Long.MAX_VALUE;
        var result = ExactScaledTaskReconciliation.reconcile(Map.of(batch, 1L), Map.of(original, total), resolver(scale));
        var parts = total.divideAndRemainder(BigInteger.valueOf(scale));
        assertEquals(Map.of(batch, parts[0], original, parts[1]), result.exact());
        assertEquals(total, result.exact().get(batch).multiply(BigInteger.valueOf(scale)).add(result.exact().get(original)));
        assertEquals(result.exact().keySet(), result.projected().keySet());
    }

    @Test void supportsBatchCountsThatAlsoExceedLong() {
        var result = ExactScaledTaskReconciliation.reconcile(Map.of(batch, 1L), Map.of(original, total), resolver(3));
        assertEquals(total.divide(BigInteger.valueOf(3)), result.exact().get(batch));
        assertEquals(Long.MAX_VALUE - 1, result.projected().get(batch));
        assertFalse(result.exact().containsKey(original));
    }

    @Test void consolidatesUnevenProviderSplitsAndKeepsUnrelatedTasks() {
        var other = pattern();
        var result = ExactScaledTaskReconciliation.reconcile(
                Map.of(batch, 2L, smallerBatch, 1L, other, 5L),
                Map.of(original, total, other, BigInteger.valueOf(5)), resolver(4));
        assertFalse(result.exact().containsKey(smallerBatch));
        assertEquals(BigInteger.valueOf(5), result.exact().get(other));
        assertEquals(total, result.exact().get(batch).multiply(BigInteger.valueOf(4)).add(result.exact().get(original)));
    }

    @Test void alreadyReconciledCopiesKeepTheirUnits() {
        var first = ExactScaledTaskReconciliation.reconcile(Map.of(batch, 1L), Map.of(original, total), resolver(4));
        var second = ExactScaledTaskReconciliation.reconcile(first.projected(), first.exact(), resolver(4));
        assertSame(first.exact(), second.exact());
        assertSame(first.projected(), second.projected());
    }

    @Test void batchLargerThanDemandBecomesOnlyRemainder() {
        var result = ExactScaledTaskReconciliation.reconcile(Map.of(batch, 1L), Map.of(original, BigInteger.TWO), resolver(4));
        assertEquals(Map.of(original, BigInteger.TWO), result.exact());
        assertEquals(Map.of(original, 2L), result.projected());
    }

    @Test void unknownAdditionalTaskRestoresAuthoritativeOriginalWork() {
        var result = assertDoesNotThrow(() -> ExactScaledTaskReconciliation.reconcile(
                Map.of(batch, 1L, pattern(), 1L), Map.of(original, total), resolver(4)));
        assertEquals(Map.of(original, total), result.exact());
        assertEquals(Map.of(original, Long.MAX_VALUE - 1), result.projected());
    }

    @Test void unavailableOrInvalidMultiplierRestoresOriginalWork() {
        for (var resolver : java.util.List.<Function<IPatternDetails, ExactScaledTaskReconciliation.ScaledTask>>of(
                p -> { throw new IllegalStateException("Unavailable optional ABI"); },
                p -> null, p -> new ExactScaledTaskReconciliation.ScaledTask(original, 0),
                p -> new ExactScaledTaskReconciliation.ScaledTask(pattern(), 4))) {
            var result = assertDoesNotThrow(() -> ExactScaledTaskReconciliation.reconcile(
                    Map.of(batch, 1L), Map.of(original, total), resolver));
            assertEquals(Map.of(original, total), result.exact());
        }
    }

    @Test void ordinaryAndAbsentMetadataPlansAreUntouched() {
        var projected = Map.of(original, 9L);
        assertSame(projected, ExactScaledTaskReconciliation.reconcile(projected, Map.of(), resolver(4)).projected());
        assertSame(projected, ExactScaledTaskReconciliation.reconcile(projected, Map.of(original, BigInteger.valueOf(9)), resolver(4)).projected());
    }

    @Test void mixedNativeAndLocalBatchesDoNotThrowOrDuplicateWork() {
        var nativeOriginal = pattern();
        var nativeBatch = new com.extendedae_plus.api.crafting.ScaledProcessingPattern(nativeOriginal, 7);
        var local = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IPatternDetails.class, com.github.appliedenhancements.integration.ae2.AelisScaledPattern.class},
                (p, m, a) -> switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "appliedenhancements$originalPattern" -> original;
                    case "appliedenhancements$operationsPerPush" -> 4L;
                    default -> throw new AssertionError(m.getName());
                });
        var result = ExactScaledTaskReconciliation.reconcile(
                Map.of(nativeBatch, 2L, nativeOriginal, 1L, local, 1L),
                Map.of(nativeOriginal, BigInteger.valueOf(15), original, BigInteger.valueOf(9)));
        assertEquals(Map.of(nativeBatch, BigInteger.TWO, nativeOriginal, BigInteger.ONE,
                local, BigInteger.TWO, original, BigInteger.ONE), result.exact());
        assertFalse(nativeBatch instanceof com.github.appliedenhancements.integration.ae2.AelisScaledPattern);
    }

    @Test void nativeRoundRobinSplitAndRemainderRetainTheirIdentities() {
        var large = new com.extendedae_plus.api.crafting.ScaledProcessingPattern(original, 4);
        var small = new com.extendedae_plus.api.crafting.ScaledProcessingPattern(original, 3);
        var projected = Map.<IPatternDetails, Long>of(large, 2L, small, 1L, original, 1L);
        var result = ExactScaledTaskReconciliation.reconcile(projected, Map.of(original, BigInteger.valueOf(12)));
        assertEquals(projected, result.projected());
        assertEquals(Map.of(large, BigInteger.TWO, small, BigInteger.ONE, original, BigInteger.ONE), result.exact());
    }

    @Test void nativeWrapperBeyondLongRetainsAllExactWork() {
        var nativeBatch = new com.extendedae_plus.api.crafting.ScaledProcessingPattern(original, 7);
        var result = ExactScaledTaskReconciliation.reconcile(Map.of(nativeBatch, 1L), Map.of(original, total));
        assertEquals(total, result.exact().get(nativeBatch).multiply(BigInteger.valueOf(7))
                .add(result.exact().getOrDefault(original, BigInteger.ZERO)));
        assertSame(nativeBatch, result.exact().keySet().stream().filter(p -> p == nativeBatch).findFirst().orElseThrow());
    }

    @Test void nativeFiniteWorkMismatchIsRepairedWithExactRemainder() {
        var nativeBatch = new com.extendedae_plus.api.crafting.ScaledProcessingPattern(original, 7);
        var result = assertDoesNotThrow(() -> ExactScaledTaskReconciliation.reconcile(
                Map.of(nativeBatch, 2L), Map.of(original, BigInteger.valueOf(15))));
        assertEquals(Map.of(nativeBatch, BigInteger.TWO, original, BigInteger.ONE), result.exact());
        assertEquals(Map.of(nativeBatch, 2L, original, 1L), result.projected());
    }

    @Test void unreadableOriginalIdentityRestoresTheWholeExactLedger() {
        var unreadable = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IPatternDetails.class}, (p, m, a) -> switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new IllegalStateException("Optional pattern identity unavailable");
                });
        var exact = Map.of(original, total);
        var result = assertDoesNotThrow(() -> ExactScaledTaskReconciliation.reconcile(Map.of(batch, 1L), exact,
                ignored -> new ExactScaledTaskReconciliation.ScaledTask(unreadable, 4)));
        assertEquals(exact, result.exact());
    }

    private static IPatternDetails pattern() {
        return (IPatternDetails) Proxy.newProxyInstance(ExactScaledTaskReconciliationTest.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.class}, (p, m, a) -> switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "getDefinition" -> null;
                    case "toString" -> "TestPattern@" + System.identityHashCode(p);
                    default -> throw new AssertionError(m.getName());
                });
    }
}
