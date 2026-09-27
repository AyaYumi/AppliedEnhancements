package com.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.appliedenhancements.CraftingOrderMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerConfigSyncStateTest {
    @AfterEach
    void clearServerSnapshot() {
        ServerConfigSyncState.reset();
    }

    @Test
    void usesLocalValuesBeforeAHandshake() {
        var local = new ServerConfigSyncState.Values(CraftingOrderMode.DISABLED, true, false);

        assertEquals(local, ServerConfigSyncState.currentOr(local));
    }

    @Test
    void serverHandshakeOverridesLocalValues() {
        var local = new ServerConfigSyncState.Values(CraftingOrderMode.DISABLED, false, false);

        ServerConfigSyncState.accept(CraftingOrderMode.LONG_MAX, true, true, false);

        assertEquals(
                new ServerConfigSyncState.Values(CraftingOrderMode.LONG_MAX, true, true),
                ServerConfigSyncState.currentOr(local));
        assertEquals(true, ServerConfigSyncState.isInfiniteStorageLimitBypassEnabled());
    }

    @Test
    void disconnectRestoresTheLocalFallback() {
        var local = new ServerConfigSyncState.Values(CraftingOrderMode.DISABLED, true, false);
        ServerConfigSyncState.accept(CraftingOrderMode.BIG_INTEGER, false, true, true);

        ServerConfigSyncState.reset();

        assertEquals(local, ServerConfigSyncState.currentOr(local));
    }

    @Test
    void rejectsAnInvalidServerMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfigSyncState.accept(null, true, true, false));
    }
}
