package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

class AelisMaximumSuccessfulPrefixTest {
    @Test
    void findsObservedSixtyOneItemPrefix() throws Exception {
        var result = AelisMaximumSuccessfulPrefix.find(
                4_464,
                amount -> amount <= 61
                        ? AelisMaximumSuccessfulPrefix.ProbeResult.applied(amount)
                        : AelisMaximumSuccessfulPrefix.ProbeResult.shortage());

        assertFalse(result.fallback());
        assertEquals(61, result.allocation());
        assertEquals(61L, result.value());
        assertTrue(result.probes() > 0);
    }

    @Test
    void retainsMaximumSuccessfulStateForExactNativeRemainder() throws Exception {
        long requested = 4_464;
        var probedAmounts = new ArrayList<Long>();
        var result = AelisMaximumSuccessfulPrefix.find(
                requested,
                amount -> {
                    probedAmounts.add(amount);
                    return amount <= 61
                            ? AelisMaximumSuccessfulPrefix.ProbeResult.applied(
                                    new AppliedPrefix(amount, 10_000 - amount))
                            : AelisMaximumSuccessfulPrefix.ProbeResult.shortage();
                });

        assertFalse(result.fallback());
        assertEquals(61, result.allocation());
        assertEquals(new AppliedPrefix(61, 9_939), result.value());
        assertEquals(4_403, requested - result.allocation());
        assertFalse(probedAmounts.contains(requested));
    }

    @Test
    void returnsZeroWhenEvenOneCannotStart() throws Exception {
        var result = AelisMaximumSuccessfulPrefix.find(
                10_000,
                amount -> AelisMaximumSuccessfulPrefix.ProbeResult.shortage());

        assertFalse(result.fallback());
        assertEquals(0, result.allocation());
        assertNull(result.value());
    }

    @Test
    void abortsWithoutReturningSpeculativeStateOnFallback() throws Exception {
        var result = AelisMaximumSuccessfulPrefix.find(
                100,
                amount -> amount == 50
                        ? AelisMaximumSuccessfulPrefix.ProbeResult.fallback()
                        : AelisMaximumSuccessfulPrefix.ProbeResult.applied(amount));

        assertTrue(result.fallback());
        assertEquals(0, result.allocation());
        assertNull(result.value());
    }

    @Test
    void handlesLongMaxWithoutMidpointOverflow() throws Exception {
        long capacity = Long.MAX_VALUE - 7;
        var result = AelisMaximumSuccessfulPrefix.find(
                Long.MAX_VALUE,
                amount -> amount <= capacity
                        ? AelisMaximumSuccessfulPrefix.ProbeResult.applied(amount)
                        : AelisMaximumSuccessfulPrefix.ProbeResult.shortage());

        assertEquals(capacity, result.allocation());
    }

    @Test
    void propagatesInterruption() {
        assertThrows(InterruptedException.class, () ->
                AelisMaximumSuccessfulPrefix.find(100, amount -> {
                    throw new InterruptedException("cancelled");
                }));
    }

    private record AppliedPrefix(long amount, long inventoryAfterCommit) {
    }
}
