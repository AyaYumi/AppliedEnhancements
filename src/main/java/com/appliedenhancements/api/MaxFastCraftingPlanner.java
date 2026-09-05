package com.appliedenhancements.api;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;

/**
 * Binary-compatible entry point for integrations compiled against Applied Enhancements 1.0.3.
 *
 * <p>New integrations should use {@link AelisCraftingPlanner}. This facade delegates
 * to its original factories without a raw crafting-service index. It preserves the
 * old callback/result types and ignores the automatic-planner enable flag. Instances
 * remain confined to one crafting calculation and are not thread-safe.</p>
 *
 * @deprecated Use {@link AelisCraftingPlanner} and the current cycle-plan integration API.
 */
@Deprecated(since = "1.0.4", forRemoval = false)
public interface MaxFastCraftingPlanner {
    PauseCheckpoint NO_PAUSE = () -> {
    };

    static MaxFastCraftingPlanner createConfigured(
            PauseCheckpoint pauseCheckpoint, ProgressListener progressListener) {
        return new MaxFastCraftingPlannerAdapter(AelisCraftingPlanner.createConfigured(
                pauseCheckpoint == null ? null : pauseCheckpoint::pause,
                MaxFastCraftingPlannerAdapter.adapt(progressListener)));
    }

    static MaxFastCraftingPlanner create(int maxNodes, int compileBudgetMillis,
            PauseCheckpoint pauseCheckpoint, ProgressListener progressListener) {
        return new MaxFastCraftingPlannerAdapter(AelisCraftingPlanner.create(
                maxNodes, compileBudgetMillis,
                pauseCheckpoint == null ? null : pauseCheckpoint::pause,
                MaxFastCraftingPlannerAdapter.adapt(progressListener)));
    }

    /** Applied/fallback/branch-failure behavior is identical to the current planner API. */
    Result tryExecute(CraftingTreeNode root, CraftingSimulationState inventory,
            long requestedAmount, boolean simulation, KeyCounter missingItems) throws InterruptedException;

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

    record Result(boolean applied, String fallbackReason, int uniqueNodes,
            long mergedOccurrences, int barrierCount, long logicalNodeCount,
            long compileNanos, long executionNanos, boolean nativeNodeCount,
            CraftBranchFailure branchFailure, Throwable error) {
        public boolean shouldFallback() {
            return !applied && branchFailure == null;
        }
    }
}

@SuppressWarnings("deprecation")
final class MaxFastCraftingPlannerAdapter implements MaxFastCraftingPlanner {
    private final AelisCraftingPlanner delegate;

    MaxFastCraftingPlannerAdapter(AelisCraftingPlanner delegate) {
        this.delegate = delegate;
    }

    @Override
    public Result tryExecute(CraftingTreeNode root, CraftingSimulationState inventory,
            long requestedAmount, boolean simulation, KeyCounter missingItems) throws InterruptedException {
        var result = delegate.tryExecute(root, inventory, requestedAmount, simulation, missingItems);
        return new Result(result.applied(), result.fallbackReason(), result.uniqueNodes(),
                result.mergedOccurrences(), result.barrierCount(), result.logicalNodeCount(),
                result.compileNanos(), result.executionNanos(), result.nativeNodeCount(),
                result.branchFailure(), result.error());
    }

    static AelisCraftingPlanner.ProgressListener adapt(ProgressListener listener) {
        if (listener == null) {
            return null;
        }
        return new AelisCraftingPlanner.ProgressListener() {
            @Override public void compilationStarted() { listener.compilationStarted(); }
            @Override public void nodeDiscovered() { listener.nodeDiscovered(); }
            @Override public void compilationStep() { listener.compilationStep(); }
            @Override public void executionStarted(long totalUnits) { listener.executionStarted(totalUnits); }
            @Override public void executionStep() { listener.executionStep(); }
        };
    }
}
