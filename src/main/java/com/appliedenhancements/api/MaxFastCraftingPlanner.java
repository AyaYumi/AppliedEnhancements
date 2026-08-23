package com.appliedenhancements.api;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import com.github.appliedenhancements.config.AppliedEnhancementsConfig;
import com.github.appliedenhancements.crafting.maxfast.OmniMaxFastPlanner;
import com.github.appliedenhancements.integration.ae2.OmniCraftingTreeNodeBridge;
import com.github.appliedenhancements.integration.ae2.OmniCraftingTreeProcessBridge;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Public integration point for delegating an AE2 crafting-tree request to
 * Applied Enhancements' MAX_FAST planner.
 *
 * <p>The built-in AE2 integration is disabled by default. Other mods may call
 * this API regardless of {@code enableAutomaticMaxFastPlanner}; that
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
            PauseCheckpoint pauseCheckpoint, ProgressListener progressListener) {
        return create(
                AppliedEnhancementsConfig.COMMON.maxFastMaxNodes.get(),
                AppliedEnhancementsConfig.COMMON.maxFastCompileBudgetMs.get(),
                pauseCheckpoint,
                progressListener);
    }

    /** Creates a planner with explicit resource budgets. */
    static MaxFastCraftingPlanner create(
            int maxNodes,
            int compileBudgetMillis,
            PauseCheckpoint pauseCheckpoint,
            ProgressListener progressListener) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive");
        }
        if (compileBudgetMillis <= 0) {
            throw new IllegalArgumentException("compileBudgetMillis must be positive");
        }
        return new MaxFastCraftingPlannerImpl(
                maxNodes,
                compileBudgetMillis,
                pauseCheckpoint == null ? NO_PAUSE : pauseCheckpoint,
                progressListener == null ? ProgressListener.NONE : progressListener);
    }

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

final class MaxFastCraftingPlannerImpl implements MaxFastCraftingPlanner {
    private final OmniMaxFastPlanner.Session delegate;

    MaxFastCraftingPlannerImpl(
            int maxNodes,
            int compileBudgetMillis,
            PauseCheckpoint pauseCheckpoint,
            ProgressListener progressListener) {
        this.delegate = new OmniMaxFastPlanner.Session(
                maxNodes,
                compileBudgetMillis,
                pauseCheckpoint::pause,
                adapt(progressListener));
    }

    @Override
    public Result tryExecute(
            CraftingTreeNode root,
            CraftingSimulationState inventory,
            long requestedAmount,
            boolean simulation,
            KeyCounter missingItems) throws InterruptedException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(missingItems, "missingItems");

        var missingSnapshot = new KeyCounter();
        missingSnapshot.addAll(missingItems);
        var possibleSnapshot = snapshotPossibleStates(root);

        OmniMaxFastPlanner.Result result;
        try {
            result = delegate.tryExecute(
                    root, inventory, requestedAmount, simulation, missingItems);
        } catch (InterruptedException | RuntimeException | Error failure) {
            restoreAttemptState(root, missingItems, missingSnapshot, possibleSnapshot);
            throw failure;
        }
        if (!result.applied()) {
            restoreAttemptState(root, missingItems, missingSnapshot, possibleSnapshot);
        }
        return new Result(
                result.applied(),
                result.fallbackReason(),
                result.uniqueNodes(),
                result.mergedOccurrences(),
                result.barrierCount(),
                result.logicalNodeCount(),
                result.compileNanos(),
                result.executionNanos(),
                result.nativeNodeCount(),
                result.branchFailure(),
                result.error());
    }

    private static OmniMaxFastPlanner.ProgressSink adapt(ProgressListener listener) {
        return new OmniMaxFastPlanner.ProgressSink() {
            @Override
            public void compilationStarted() {
                listener.compilationStarted();
            }

            @Override
            public void nodeDiscovered() {
                listener.nodeDiscovered();
            }

            @Override
            public void compilationStep() {
                listener.compilationStep();
            }

            @Override
            public void executionStarted(long totalUnits) {
                listener.executionStarted(totalUnits);
            }

            @Override
            public void executionStep() {
                listener.executionStep();
            }
        };
    }

    private static IdentityHashMap<CraftingTreeProcess, Boolean>
            snapshotPossibleStates(CraftingTreeNode root) {
        var result = new IdentityHashMap<CraftingTreeProcess, Boolean>();
        visitBuiltProcesses(root, process -> result.put(
                process,
                ((OmniCraftingTreeProcessBridge) process)
                        .molecularmanipulator$isPossible()));
        return result;
    }

    private static void restoreAttemptState(
            CraftingTreeNode root,
            KeyCounter missingItems,
            KeyCounter missingSnapshot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot) {
        missingItems.clear();
        missingItems.addAll(missingSnapshot);
        visitBuiltProcesses(root, process -> {
            Boolean previous = possibleSnapshot.get(process);
            ((OmniCraftingTreeProcessBridge) process)
                    .molecularmanipulator$setPossible(previous == null || previous);
        });
    }

    private static void visitBuiltProcesses(
            CraftingTreeNode root,
            Consumer<CraftingTreeProcess> visitor) {
        var pending = new ArrayDeque<CraftingTreeNode>();
        var visited = new IdentityHashMap<CraftingTreeNode, Boolean>();
        pending.addLast(root);
        while (!pending.isEmpty()) {
            CraftingTreeNode node = pending.removeFirst();
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            var bridge = (OmniCraftingTreeNodeBridge) node;
            var processes = bridge.molecularmanipulator$getProcesses();
            if (processes == null) {
                continue;
            }
            for (CraftingTreeProcess process : processes) {
                visitor.accept(process);
                var processBridge = (OmniCraftingTreeProcessBridge) process;
                var children = processBridge.molecularmanipulator$getChildNodes();
                if (children != null) {
                    pending.addAll(children.keySet());
                }
            }
        }
    }
}
