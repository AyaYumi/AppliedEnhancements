package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressPhase;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPath;
import org.junit.jupiter.api.Test;

class ExternalFutureProgressLifecycleTest {
    @Test
    void externalPlannerSuccessMovesQueuedHandleToCompletedOnNextAe2Poll() {
        var handle = new CraftingCalculationProgressHandle(1);
        var delegate = new CompletableFuture<String>();
        var future = new TerminalAwareFuture<>(
                delegate,
                result -> handle.complete(OmniCalculationPath.ECOAE),
                failure -> handle.fail(),
                handle::cancel);

        assertFalse(handle.terminal());
        delegate.complete("standard-crafting-plan");
        assertFalse(handle.terminal(),
                "The independent future does not execute CraftingCalculation.run()");

        assertTrue(future.isDone(), "AE2 polls isDone() from CraftConfirmMenu.broadcastChanges()");
        assertTrue(handle.terminal());
        var snapshot = handle.snapshot(1);
        assertEquals(CraftingCalculationProgressPhase.COMPLETED, snapshot.phase());
        assertEquals(OmniCalculationPath.ECOAE, snapshot.path());
    }

    @Test
    void externalPlannerFailureAndCancellationProduceTerminalSnapshots() {
        var failedHandle = new CraftingCalculationProgressHandle(2);
        var failedDelegate = new CompletableFuture<String>();
        var failedFuture = new TerminalAwareFuture<>(
                failedDelegate,
                result -> failedHandle.complete(OmniCalculationPath.ECOAE),
                failure -> failedHandle.fail(),
                failedHandle::cancel);
        failedDelegate.completeExceptionally(new IllegalStateException("external failure"));
        assertTrue(failedFuture.isDone());
        assertEquals(CraftingCalculationProgressPhase.FAILED,
                failedHandle.snapshot(1).phase());

        var cancelledHandle = new CraftingCalculationProgressHandle(3);
        var cancelledDelegate = new CompletableFuture<String>();
        var cancelledFuture = new TerminalAwareFuture<>(
                cancelledDelegate,
                result -> cancelledHandle.complete(OmniCalculationPath.ECOAE),
                failure -> cancelledHandle.fail(),
                cancelledHandle::cancel);
        cancelledDelegate.cancel(true);
        assertTrue(cancelledFuture.isDone());
        assertEquals(CraftingCalculationProgressPhase.CANCELLED,
                cancelledHandle.snapshot(1).phase());
    }

    @Test
    void disabledNewPlanningTaskDetachesOldTerminalHandleAcrossHotEnable() {
        var binding = new CraftingProgressTaskBinding();
        var oldHandle = new CraftingCalculationProgressHandle(4);
        var oldTask = binding.begin(oldHandle);
        oldHandle.complete(OmniCalculationPath.ECOAE);

        assertSame(oldHandle, binding.currentProgress());
        assertTrue(oldHandle.terminal());

        // progressDisplay=false: the new future still gets its own task binding,
        // but no progress handle. This must evict the old completed handle.
        var untrackedTask = binding.begin(null);
        assertTrue(binding.isCurrent(untrackedTask));
        assertFalse(binding.isCurrent(oldTask));
        assertNull(binding.currentProgress());

        // progressDisplay=true is hot-reloaded while the same future remains
        // current. Sending reads the current binding and cannot resurrect the
        // old task's terminal snapshot.
        assertNull(binding.currentProgress());
    }
}
