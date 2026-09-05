package com.appliedenhancements.api;

import appeng.api.stacks.KeyCounter;
import appeng.api.networking.crafting.ICraftingService;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.Config;
import com.github.appliedenhancements.crafting.aelis.AelisPlanner;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeNodeBridge;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeProcessBridge;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Public integration point for the Applied Enhancements Lattice Integer Solver.
 *
 * <p>The built-in AE2 integration is disabled by default. Other mods may call
 * this API regardless of {@code crafting.aelis.enable_automatic_planner}; that
 * configuration only controls whether Applied Enhancements automatically
 * intercepts AE2's native calculation.</p>
 *
 * <p>Create one planner instance per AE2 crafting calculation and reuse it for
 * that calculation's real and simulated attempts. A planner instance is not
 * thread-safe and must not be shared between calculation roots.</p>
 */
public interface AelisCraftingPlanner {
    /** A no-op pause checkpoint suitable for integrations without cooperative pausing. */
    PauseCheckpoint NO_PAUSE = () -> {
    };

    /**
     * Creates a planner using the server's configured node and compilation
     * budgets. The automatic-integration enable flag is deliberately ignored.
     */
    static AelisCraftingPlanner createConfigured(
            PauseCheckpoint pauseCheckpoint, ProgressListener progressListener) {
        return create(
                Config.AELIS_MAX_NODES.get(),
                Config.AELIS_COMPILE_BUDGET_MS.get(),
                pauseCheckpoint,
                progressListener);
    }

    /**
     * Creates a configured planner that can recover exact cyclic candidates
     * hidden by AE2's recursion-filtered tree from the service's existing index.
     */
    static AelisCraftingPlanner createConfigured(
            PauseCheckpoint pauseCheckpoint, ProgressListener progressListener,
            ICraftingService craftingService) {
        return create(
                Config.AELIS_MAX_NODES.get(),
                Config.AELIS_COMPILE_BUDGET_MS.get(),
                pauseCheckpoint,
                progressListener,
                Objects.requireNonNull(craftingService, "craftingService"));
    }

    /** Creates a planner with explicit resource budgets. */
    static AelisCraftingPlanner create(
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
        return new AelisCraftingPlannerImpl(
                maxNodes,
                compileBudgetMillis,
                pauseCheckpoint == null ? NO_PAUSE : pauseCheckpoint,
                progressListener == null ? ProgressListener.NONE : progressListener,
                null);
    }

    /** Creates a planner with explicit budgets and an on-demand raw pattern index. */
    static AelisCraftingPlanner create(
            int maxNodes,
            int compileBudgetMillis,
            PauseCheckpoint pauseCheckpoint,
            ProgressListener progressListener,
            ICraftingService craftingService) {
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive");
        }
        if (compileBudgetMillis <= 0) {
            throw new IllegalArgumentException("compileBudgetMillis must be positive");
        }
        return new AelisCraftingPlannerImpl(
                maxNodes,
                compileBudgetMillis,
                pauseCheckpoint == null ? NO_PAUSE : pauseCheckpoint,
                progressListener == null ? ProgressListener.NONE : progressListener,
                Objects.requireNonNull(craftingService, "craftingService"));
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

final class AelisCraftingPlannerImpl implements AelisCraftingPlanner {
    private final AelisPlanner.Session delegate;

    AelisCraftingPlannerImpl(
            int maxNodes,
            int compileBudgetMillis,
            PauseCheckpoint pauseCheckpoint,
            ProgressListener progressListener,
            ICraftingService craftingService) {
        this.delegate = new AelisPlanner.Session(
                maxNodes,
                compileBudgetMillis,
                pauseCheckpoint::pause,
                adapt(progressListener),
                craftingService);
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

        AelisPlanner.Result result;
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

    private static AelisPlanner.ProgressSink adapt(ProgressListener listener) {
        return new AelisPlanner.ProgressSink() {
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
                ((AelisCraftingTreeProcessBridge) process)
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
            ((AelisCraftingTreeProcessBridge) process)
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
            var bridge = (AelisCraftingTreeNodeBridge) node;
            var processes = bridge.molecularmanipulator$getProcesses();
            if (processes == null) {
                continue;
            }
            for (CraftingTreeProcess process : processes) {
                visitor.accept(process);
                var processBridge = (AelisCraftingTreeProcessBridge) process;
                var children = processBridge.molecularmanipulator$getChildNodes();
                if (children != null) {
                    pending.addAll(children.keySet());
                }
            }
        }
    }
}
