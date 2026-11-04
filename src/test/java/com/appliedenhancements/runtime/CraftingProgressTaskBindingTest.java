package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressPhase;
import org.junit.jupiter.api.Test;

class CraftingProgressTaskBindingTest {
    @Test
    void disabledNewPlanningTaskDetachesOldTerminalHandleAcrossHotEnable() {
        var binding = new CraftingProgressTaskBinding();
        var oldHandle = new CraftingCalculationProgressHandle(1);
        binding.begin(oldHandle);
        oldHandle.complete(AelisCalculationPath.AELIS);

        assertSame(oldHandle, binding.currentProgress());
        assertTrue(oldHandle.terminal());

        // A task started without progress must not expose the previous plan after a config reload.
        binding.begin(null);
        assertNull(binding.currentProgress());
    }

    @Test
    void replacingOrClearingATaskCancelsItsActiveProgress() {
        var binding = new CraftingProgressTaskBinding();
        var first = new CraftingCalculationProgressHandle(1);
        var second = new CraftingCalculationProgressHandle(2);
        binding.begin(first);
        binding.begin(second);

        assertEquals(CraftingCalculationProgressPhase.CANCELLED, first.snapshot(1).phase());
        assertSame(second, binding.currentProgress());

        binding.clear();
        binding.clear();
        assertEquals(CraftingCalculationProgressPhase.CANCELLED, second.snapshot(1).phase());
        assertNull(binding.currentProgress());
    }
}
