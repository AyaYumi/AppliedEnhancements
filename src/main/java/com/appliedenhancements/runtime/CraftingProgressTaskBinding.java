package com.appliedenhancements.runtime;

import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import org.jetbrains.annotations.ApiStatus;

/** Keeps only the current AELIS progress handle, including when display is disabled. */
@ApiStatus.Internal
public final class CraftingProgressTaskBinding {
    private CraftingCalculationProgressHandle current;

    public void begin(CraftingCalculationProgressHandle progress) {
        clear();
        current = progress;
    }

    public CraftingCalculationProgressHandle currentProgress() {
        return current;
    }

    public void clear() {
        var previous = current;
        current = null;
        if (previous != null) {
            previous.cancel();
        }
    }
}
