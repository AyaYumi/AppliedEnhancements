package com.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class NetworkItemExtractPayloadTest {
    @Test
    void roundTripsLongAmountAndSerial() {
        var expected = new NetworkItemExtractPayload(
                Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        var buffer = Unpooled.buffer();
        try {
            NetworkItemExtractPayload.STREAM_CODEC.encode(buffer, expected);
            assertEquals(
                    expected,
                    NetworkItemExtractPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsInvalidRequestsBeforeSending() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkItemExtractPayload(-1, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkItemExtractPayload(1, -1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkItemExtractPayload(1, 0, 0));
    }
}
