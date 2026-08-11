package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OmniOrderedChoiceFallbackTest {
    private static final long NATIVE_ITEM_LIMIT = 1_000_000;

    @Test
    void unsafeSimulationResultCannotBeCommitted() {
        assertFalse(OmniOrderedChoiceFallback.mayCommitCompiledResult(
                true, false));
        assertTrue(OmniOrderedChoiceFallback.mayCommitCompiledResult(
                true, true));
        assertTrue(OmniOrderedChoiceFallback.mayCommitCompiledResult(
                false, false));
    }

    @Test
    void oneItemMayStillUseNativeAfterCompiledShortage() {
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                decide(1, 1));
    }

    @Test
    void exactConfiguredLimitRemainsNativeAndNextItemIsRejected() {
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                decide(1, NATIVE_ITEM_LIMIT));
        assertEquals(
                OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, NATIVE_ITEM_LIMIT + 1,
                        NATIVE_ITEM_LIMIT + 1));
    }

    @Test
    void boundedRootCannotEnterUnboundedLocalNativeReplay() {
        assertEquals(
                OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, 1_024_000L, 10_000L));
    }

    @Test
    void diamondOneBillionAndTenBillionDoNotEnterLinearNativeReplay() {
        assertEquals(
                OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, 1_000_000_000L, 1_000_000_000L));
        assertEquals(
                OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(1, 10_000_000_000L, 10_000_000_000L));
    }

    @Test
    void nodeAmountParticipatesInTheLinearWorkBound() {
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                decide(10, 100_000));
        assertEquals(
                OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(10, 100_001, NATIVE_ITEM_LIMIT + 1));
    }

    @Test
    void multiplicationOverflowSaturatesToControlledReject() {
        assertEquals(
                OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decide(Long.MAX_VALUE, 2, Long.MAX_VALUE));
    }

    @Test
    void controlledLargeResultDoesNotDependOnInterruption() {
        var decision = assertDoesNotThrow(
                () -> decide(1, 10_000_000_000L,
                        10_000_000_000L));

        assertEquals(
                OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT,
                decision);
    }

    @Test
    void safeModeAndNonPositiveWorkPreserveNativeBehavior() {
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                OmniOrderedChoiceFallback.afterCompiledFailure(
                        OmniMaxFastMode.SAFE, 1, 10_000_000_000L,
                        10_000_000_000L,
                        NATIVE_ITEM_LIMIT));
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                decide(0, 10_000_000_000L));
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                decide(1, 0));
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                decide(-1, 10_000_000_000L));
        assertEquals(
                OmniOrderedChoiceFallback.Decision.NATIVE,
                decide(1, -1));
    }

    @Test
    void negativeConfiguredLimitIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OmniOrderedChoiceFallback.afterCompiledFailure(
                        OmniMaxFastMode.AGGRESSIVE, 1, 1, 1, -1));
    }

    private static OmniOrderedChoiceFallback.Decision decide(
            long nodeAmount, long requestMultipliers) {
        return decide(nodeAmount, requestMultipliers, requestMultipliers);
    }

    private static OmniOrderedChoiceFallback.Decision decide(
            long nodeAmount, long requestMultipliers,
            long rootRequestedAmount) {
        return OmniOrderedChoiceFallback.afterCompiledFailure(
                OmniMaxFastMode.AGGRESSIVE,
                nodeAmount,
                requestMultipliers,
                rootRequestedAmount,
                NATIVE_ITEM_LIMIT);
    }
}
