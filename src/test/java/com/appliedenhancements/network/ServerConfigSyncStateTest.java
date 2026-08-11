package com.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerConfigSyncStateTest {
    @AfterEach
    void clearServerSnapshot() {
        ServerConfigSyncState.reset();
    }

    @Test
    void usesLocalValuesBeforeAHandshake() {
        var local = new ServerConfigSyncState.Values(128, false, true);

        assertEquals(local, ServerConfigSyncState.currentOr(local));
    }

    @Test
    void serverHandshakeOverridesLocalValues() {
        var local = new ServerConfigSyncState.Values(128, false, false);

        ServerConfigSyncState.accept(9_000_000_000L, true, true);

        assertEquals(
                new ServerConfigSyncState.Values(9_000_000_000L, true, true),
                ServerConfigSyncState.currentOr(local));
    }

    @Test
    void disconnectRestoresTheLocalFallback() {
        var local = new ServerConfigSyncState.Values(512, false, true);
        ServerConfigSyncState.accept(Long.MAX_VALUE, true, false);

        ServerConfigSyncState.reset();

        assertEquals(local, ServerConfigSyncState.currentOr(local));
    }

    @Test
    void rejectsAnInvalidServerMaximum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfigSyncState.accept(0, true, true));
    }
}
