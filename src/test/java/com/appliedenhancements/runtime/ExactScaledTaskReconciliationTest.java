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

    @Test void unknownAdditionalTaskIsNotSilentlyDropped() {
        assertThrows(IllegalStateException.class, () -> ExactScaledTaskReconciliation.reconcile(
                Map.of(batch, 1L, pattern(), 1L), Map.of(original, total), resolver(4)));
    }

    @Test void ordinaryAndAbsentMetadataPlansAreUntouched() {
        var projected = Map.of(original, 9L);
        assertSame(projected, ExactScaledTaskReconciliation.reconcile(projected, Map.of(), resolver(4)).projected());
        assertSame(projected, ExactScaledTaskReconciliation.reconcile(projected, Map.of(original, BigInteger.valueOf(9)), resolver(4)).projected());
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
