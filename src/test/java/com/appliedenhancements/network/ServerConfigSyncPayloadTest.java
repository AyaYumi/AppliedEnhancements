package com.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ServerConfigSyncPayloadTest {
    @Test
    void roundTripsTheFullLongRange() {
        var expected = new ServerConfigSyncPayload(Long.MAX_VALUE, true, false, true);
        var buffer = Unpooled.buffer();
        try {
            ServerConfigSyncPayload.STREAM_CODEC.encode(buffer, expected);

            assertEquals(expected, ServerConfigSyncPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsInvalidMaximums() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerConfigSyncPayload(0, true, true, true));
    }
}
