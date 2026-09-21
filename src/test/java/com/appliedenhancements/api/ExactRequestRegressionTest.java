package com.appliedenhancements.api;

import com.appliedenhancements.ae2.ExactAmountParser;
import com.appliedenhancements.network.ExactCraftingAmountPayload;
import com.appliedenhancements.runtime.ExactRequestScope;
import java.math.BigInteger;
import java.text.DecimalFormat;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactRequestRegressionTest {
    @Test void literalInputAndWireNeverWrapOrRound() {
        for (var text : new String[]{"9223372036854775807", "9223372036854775808", "9999999999999999999", "99999999999999999999", "9".repeat(256)}) {
            var value = ExactAmountParser.parse(text, new DecimalFormat(), 1).orElseThrow();
            assertEquals(new BigInteger(text), value);
            var payload = new ExactCraftingAmountPayload(42, text, true, false);
            var buffer = Unpooled.buffer();
            try { ExactCraftingAmountPayload.STREAM_CODEC.encode(buffer, payload);
                assertEquals(payload, ExactCraftingAmountPayload.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
        assertTrue(ExactAmountParser.parse("9".repeat(257), new DecimalFormat(), 1).isEmpty());
        assertTrue(ExactAmountParser.parse("-1", new DecimalFormat(), 1).isEmpty());
        assertTrue(ExactAmountParser.parse("0", new DecimalFormat(), 1).isEmpty());
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
