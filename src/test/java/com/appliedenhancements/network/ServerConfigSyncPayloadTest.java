package com.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.appliedenhancements.CraftingOrderMode;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ServerConfigSyncPayloadTest {
    @Test
    void roundTripsTheFullLongRange() {
        var expected = new ServerConfigSyncPayload(CraftingOrderMode.BIG_INTEGER, false, true);
        var buffer = new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerConfigSyncPayload.STREAM_CODEC.encode(buffer, expected);

            assertEquals(expected, ServerConfigSyncPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsInvalidMaximums() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfigSyncPayload(-1, true, true, false));
    }
}
