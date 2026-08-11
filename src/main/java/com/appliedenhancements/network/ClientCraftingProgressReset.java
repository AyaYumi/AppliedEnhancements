package com.appliedenhancements.network;

import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import org.jetbrains.annotations.ApiStatus;

/** Client-side reset routing for crafting progress presentation state. */
@ApiStatus.Internal
public final class ClientCraftingProgressReset {
    private ClientCraftingProgressReset() {
    }

    /**
     * Resets each distinct bridge when progress display is disabled.
     *
     * <p>The candidates may represent both the player's current menu and the
     * menu retained by the current container screen.
     *
     * @return the number of distinct progress bridges reset
     */
    public static int resetIfDisabled(
            boolean progressDisplayEnabled, Object... candidates) {
        if (progressDisplayEnabled) {
            return 0;
        }
        return resetDistinct(candidates);
    }

    /** Clears both the server snapshot and visible progress across a connection boundary. */
    public static int resetForConnectionChange(Object... candidates) {
        ServerConfigSyncState.reset();
        return resetDistinct(candidates);
    }

    /** Resets each distinct progress bridge, ignoring null and unrelated values. */
    public static int resetDistinct(Object... candidates) {
        int resetCount = 0;
        for (int index = 0; index < candidates.length; index++) {
            Object candidate = candidates[index];
            if (!(candidate instanceof CraftingCalculationProgressMenuBridge bridge)
                    || appearedEarlier(candidates, index, candidate)) {
                continue;
            }
            bridge.molecularmanipulator$resetCalculationProgress();
            resetCount++;
        }
        return resetCount;
    }

    private static boolean appearedEarlier(
            Object[] candidates, int currentIndex, Object candidate) {
        for (int index = 0; index < currentIndex; index++) {
            if (candidates[index] == candidate) {
                return true;
            }
        }
        return false;
    }
}
