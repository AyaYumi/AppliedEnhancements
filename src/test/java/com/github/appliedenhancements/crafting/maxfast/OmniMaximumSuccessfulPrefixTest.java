package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

class OmniMaximumSuccessfulPrefixTest {
    @Test
    void findsObservedSixtyOneItemPrefix() throws Exception {
        var result = OmniMaximumSuccessfulPrefix.find(
                4_464,
                amount -> amount <= 61
                        ? OmniMaximumSuccessfulPrefix.ProbeResult.applied(amount)
                        : OmniMaximumSuccessfulPrefix.ProbeResult.shortage());

        assertFalse(result.fallback());
        assertEquals(61, result.allocation());
        assertEquals(61L, result.value());
        assertTrue(result.probes() > 0);
    }

    @Test
    void retainsMaximumSuccessfulStateForExactNativeRemainder() throws Exception {
        long requested = 4_464;
        var probedAmounts = new ArrayList<Long>();
        var result = OmniMaximumSuccessfulPrefix.find(
                requested,
                amount -> {
                    probedAmounts.add(amount);
                    return amount <= 61
                            ? OmniMaximumSuccessfulPrefix.ProbeResult.applied(
                                    new AppliedPrefix(amount, 10_000 - amount))
                            : OmniMaximumSuccessfulPrefix.ProbeResult.shortage();
                });

        assertFalse(result.fallback());
        assertEquals(61, result.allocation());
        assertEquals(new AppliedPrefix(61, 9_939), result.value());
        assertEquals(4_403, requested - result.allocation());
        assertFalse(probedAmounts.contains(requested));
    }

    @Test
    void returnsZeroWhenEvenOneCannotStart() throws Exception {
        var result = OmniMaximumSuccessfulPrefix.find(
                10_000,
                amount -> OmniMaximumSuccessfulPrefix.ProbeResult.shortage());

        assertFalse(result.fallback());
        assertEquals(0, result.allocation());
        assertNull(result.value());
    }

    @Test
    void abortsWithoutReturningSpeculativeStateOnFallback() throws Exception {
        var result = OmniMaximumSuccessfulPrefix.find(
                100,
                amount -> amount == 50
                        ? OmniMaximumSuccessfulPrefix.ProbeResult.fallback()
                        : OmniMaximumSuccessfulPrefix.ProbeResult.applied(amount));

        assertTrue(result.fallback());
        assertEquals(0, result.allocation());
        assertNull(result.value());
    }

    @Test
    void handlesLongMaxWithoutMidpointOverflow() throws Exception {
        long capacity = Long.MAX_VALUE - 7;
        var result = OmniMaximumSuccessfulPrefix.find(
                Long.MAX_VALUE,
                amount -> amount <= capacity
                        ? OmniMaximumSuccessfulPrefix.ProbeResult.applied(amount)
                        : OmniMaximumSuccessfulPrefix.ProbeResult.shortage());

        assertEquals(capacity, result.allocation());
    }

    @Test
    void propagatesInterruption() {
        assertThrows(InterruptedException.class, () ->
                OmniMaximumSuccessfulPrefix.find(100, amount -> {
                    throw new InterruptedException("cancelled");
                }));
    }

    private record AppliedPrefix(long amount, long inventoryAfterCommit) {
    }
}
