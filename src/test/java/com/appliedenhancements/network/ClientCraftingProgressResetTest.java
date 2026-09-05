package com.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressPhase;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientCraftingProgressResetTest {
    @AfterEach
    void clearServerSnapshot() {
        ServerConfigSyncState.reset();
    }

    @Test
    void disabledServerSnapshotBypassesOrderingAndClearsActiveProgress() {
        var menu = new FakeProgressMenu(activeProgress(12, 7));

        assertEquals(1, ClientCraftingProgressReset.resetIfDisabled(false, menu));
        assertSame(CraftingCalculationProgressSnapshot.idle(),
                menu.molecularmanipulator$getCalculationProgress());
        assertEquals(0, menu.orderedAcceptCalls);
        assertEquals(1, menu.resetCalls);
    }

    @Test
    void enabledServerSnapshotPreservesCurrentProgress() {
        var active = activeProgress(20, 3);
        var menu = new FakeProgressMenu(active);

        assertEquals(0, ClientCraftingProgressReset.resetIfDisabled(true, menu));
        assertSame(active, menu.molecularmanipulator$getCalculationProgress());
        assertEquals(0, menu.resetCalls);
    }

    @Test
    void connectionChangeResetsDistinctPlayerAndScreenMenusAndServerSnapshot() {
        var playerMenu = new FakeProgressMenu(activeProgress(30, 1));
        var screenMenu = new FakeProgressMenu(activeProgress(31, 1));
        var fallback = new ServerConfigSyncState.Values(128, false, true, false);
        ServerConfigSyncState.accept(Long.MAX_VALUE, true, false, true);

        assertEquals(2, ClientCraftingProgressReset.resetForConnectionChange(
                playerMenu, screenMenu, playerMenu, null, new Object()));

        assertSame(CraftingCalculationProgressSnapshot.idle(),
                playerMenu.molecularmanipulator$getCalculationProgress());
        assertSame(CraftingCalculationProgressSnapshot.idle(),
                screenMenu.molecularmanipulator$getCalculationProgress());
        assertEquals(1, playerMenu.resetCalls);
        assertEquals(1, screenMenu.resetCalls);
        assertEquals(fallback, ServerConfigSyncState.currentOr(fallback));
    }

    private static CraftingCalculationProgressSnapshot activeProgress(
            long generation, long revision) {
        return new CraftingCalculationProgressSnapshot(
                generation,
                revision,
                CraftingCalculationProgressPhase.AE2_CALCULATING,
                AelisCalculationPath.AE2_NATIVE,
                1,
                1,
                0,
                -1,
                10,
                1,
                false);
    }

    private static final class FakeProgressMenu
            implements CraftingCalculationProgressMenuBridge {
        private CraftingCalculationProgressSnapshot progress;
        private int orderedAcceptCalls;
        private int resetCalls;

        private FakeProgressMenu(CraftingCalculationProgressSnapshot progress) {
            this.progress = progress;
        }

        @Override
        public CraftingCalculationProgressSnapshot molecularmanipulator$getCalculationProgress() {
            return progress;
        }

        @Override
        public boolean molecularmanipulator$acceptCalculationProgress(
                CraftingCalculationProgressSnapshot incoming) {
            orderedAcceptCalls++;
            return false;
        }

        @Override
        public void molecularmanipulator$resetCalculationProgress() {
            progress = CraftingCalculationProgressSnapshot.idle();
            resetCalls++;
        }
    }
}
