// Public declaration copied from release b2c35c9 (1.0.3); factory bodies are compile-only.
package com.appliedenhancements.api;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Public integration point for delegating an AE2 crafting-tree request to
 * Applied Enhancements' MAX_FAST planner.
 *
 * <p>The built-in AE2 integration is disabled by default. Other mods may call
 * this API regardless of {@code crafting.max_fast.enable_automatic_planner}; that
 * configuration only controls whether Applied Enhancements automatically
 * intercepts AE2's native calculation.</p>
 *
 * <p>Create one planner instance per AE2 crafting calculation and reuse it for
 * that calculation's real and simulated attempts. A planner instance is not
 * thread-safe and must not be shared between calculation roots.</p>
 */
public interface MaxFastCraftingPlanner {
    /** A no-op pause checkpoint suitable for integrations without cooperative pausing. */
    PauseCheckpoint NO_PAUSE = () -> {
    };

    /**
     * Creates a planner using the server's configured node and compilation
     * budgets. The automatic-integration enable flag is deliberately ignored.
     */
    static MaxFastCraftingPlanner createConfigured(
            PauseCheckpoint pauseCheckpoint, ProgressListener progressListener) { throw new UnsupportedOperationException("Compile-only 1.0.3 ABI declaration"); }

    /** Creates a planner with explicit resource budgets. */
    static MaxFastCraftingPlanner create(
            int maxNodes,
            int compileBudgetMillis,
            PauseCheckpoint pauseCheckpoint,
            ProgressListener progressListener) { throw new UnsupportedOperationException("Compile-only 1.0.3 ABI declaration"); }

    /**
     * Attempts to plan and apply one tree request.
     *
     * <p>If the result is not applied, the missing-item counter and AE2
     * candidate state are restored before this method returns. The caller may
     * therefore invoke its own planner or AE2's native request path when
     * {@link Result#shouldFallback()} is true. A non-null
     * {@link Result#branchFailure()} is a terminal AE2 branch failure and should
     * normally be propagated instead of falling back.</p>
     */
    Result tryExecute(
            CraftingTreeNode root,
            CraftingSimulationState inventory,
            long requestedAmount,
            boolean simulation,
            KeyCounter missingItems) throws InterruptedException;

    @FunctionalInterface
    interface PauseCheckpoint {
        void pause() throws InterruptedException;
    }

    interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void compilationStarted() {
        }

        default void nodeDiscovered() {
        }

        default void compilationStep() {
        }

        default void executionStarted(long totalUnits) {
        }

        default void executionStep() {
        }
    }

    record Result(
            boolean applied,
            String fallbackReason,
            int uniqueNodes,
            long mergedOccurrences,
            int barrierCount,
            long logicalNodeCount,
            long compileNanos,
            long executionNanos,
            boolean nativeNodeCount,
            CraftBranchFailure branchFailure,
            Throwable error) {
        public boolean shouldFallback() {
            return !applied && branchFailure == null;
        }
    }
}
