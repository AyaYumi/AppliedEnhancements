package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AelisOrderedChoiceFallbackTest {
    private static final long NATIVE_ITEM_LIMIT = 1_000_000;

    @Test
    void unsafeSimulationResultCannotBeCommitted() {
        assertFalse(AelisOrderedChoiceFallback.mayCommitCompiledResult(
                true, false));
        assertTrue(AelisOrderedChoiceFallback.mayCommitCompiledResult(
                true, true));
        assertTrue(AelisOrderedChoiceFallback.mayCommitCompiledResult(
                false, false));
    }

    @Test
    void oneItemMayStillUseNativeAfterCompiledShortage() {
        assertEquals(
                AelisOrderedChoiceFallback.Decision.NATIVE,
                decide(1, 1));
    }

    @Test
    void exactConfiguredLimitRemainsNativeAndNextItemIsRejected() {
        assertEquals(
                AelisOrderedChoiceFallback.Decision.NATIVE,
                decide(1, NATIVE_ITEM_LIMIT));
        assertEquals(
                AelisOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, NATIVE_ITEM_LIMIT + 1,
                        NATIVE_ITEM_LIMIT + 1));
    }

    @Test
    void boundedRootCannotEnterUnboundedLocalNativeReplay() {
        assertEquals(
                AelisOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, 1_024_000L, 10_000L));
    }

    @Test
    void diamondOneBillionAndTenBillionDoNotEnterLinearNativeReplay() {
        assertEquals(
                AelisOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, 1_000_000_000L, 1_000_000_000L));
        assertEquals(
                AelisOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, 10_000_000_000L, 10_000_000_000L));
    }

    @Test
    void nodeAmountParticipatesInTheLinearWorkBound() {
        assertEquals(
                AelisOrderedChoiceFallback.Decision.NATIVE,
                decide(10, 100_000));
        assertEquals(
                AelisOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(10, 100_001, NATIVE_ITEM_LIMIT + 1));
    }

    @Test
    void multiplicationOverflowSaturatesToControlledReject() {
        assertEquals(
                AelisOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(Long.MAX_VALUE, 2, Long.MAX_VALUE));
    }

    @Test
    void controlledLargeResultDoesNotDependOnInterruption() {
        var decision = assertDoesNotThrow(
                () -> decide(1, 10_000_000_000L,
                        10_000_000_000L));

        assertEquals(
                AelisOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decision);
    }

    @Test
    void nonPositiveWorkPreservesNativeBehavior() {
        assertEquals(
                AelisOrderedChoiceFallback.Decision.NATIVE,
                decide(0, 10_000_000_000L));
        assertEquals(
                AelisOrderedChoiceFallback.Decision.NATIVE,
                decide(1, 0));
        assertEquals(
                AelisOrderedChoiceFallback.Decision.NATIVE,
                decide(-1, 10_000_000_000L));
        assertEquals(
                AelisOrderedChoiceFallback.Decision.NATIVE,
                decide(1, -1));
    }

    @Test
    void negativeConfiguredLimitIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AelisOrderedChoiceFallback.afterCompiledFailure(
                        1, 1, 1, -1));
    }

    private static AelisOrderedChoiceFallback.Decision decide(
            long nodeAmount, long requestMultipliers) {
        return decide(nodeAmount, requestMultipliers, requestMultipliers);
    }

    private static AelisOrderedChoiceFallback.Decision decide(
            long nodeAmount, long requestMultipliers,
            long rootRequestedAmount) {
        return AelisOrderedChoiceFallback.afterCompiledFailure(
                nodeAmount,
                requestMultipliers,
                rootRequestedAmount,
                NATIVE_ITEM_LIMIT);
    }
}
