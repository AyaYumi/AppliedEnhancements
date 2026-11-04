package com.appliedenhancements.api;

import com.appliedenhancements.runtime.ExactRequestScope;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactRequestRegressionTest {
    @Test void inputAndWirePreserveQuantitiesBeyondLong() {
        var amount = BigInteger.TEN.pow(60).add(BigInteger.ONE);
        assertEquals(amount, com.appliedenhancements.ae2.ExactAmountParser.parse(amount.toString(),
                new java.text.DecimalFormat(), 1).orElseThrow());
        var payload = new com.appliedenhancements.network.ExactCraftingAmountPayload(
                7, amount.toString(), true, false);
        var buffer = io.netty.buffer.Unpooled.buffer();
        try {
            com.appliedenhancements.network.ExactCraftingAmountPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, com.appliedenhancements.network.ExactCraftingAmountPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test void deliveryCrossesLongBoundaryWithoutFinishingEarlyAndCanResume() {
        var amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.TEN);
        var state = new AelisExactOutputProgress(amount);
        state.delivered(Long.MAX_VALUE);
        assertFalse(state.complete());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN), state.remaining());
        var restored = new AelisExactOutputProgress(new BigInteger(state.remaining().toString()));
        restored.delivered(Long.MAX_VALUE);
        assertEquals(10, restored.window());
        restored.delivered(9);
        assertFalse(restored.complete());
        restored.delivered(1);
        assertTrue(restored.complete());
    }
    @Test void requestScopeRestoresAfterNestedFailure() {
        assertNull(ExactRequestScope.current());
        var request = new AelisExactRequest(BigInteger.TEN);
        try (var outer = new ExactRequestScope(request)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = new ExactRequestScope(new AelisExactRequest(BigInteger.ONE))) {
                    throw new IllegalStateException();
                }
            });
            assertSame(request, ExactRequestScope.current());
        }
        assertNull(ExactRequestScope.current());
    }
}
