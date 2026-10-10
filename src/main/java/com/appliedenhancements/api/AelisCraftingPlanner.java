package com.appliedenhancements.api;

import appeng.api.stacks.KeyCounter;
import appeng.api.networking.crafting.ICraftingService;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.Config;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import com.github.appliedenhancements.crafting.aelis.AelisPlanner;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeNodeBridge;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeProcessBridge;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
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
                craftingService);
    }

    /** Creates a planner with explicit resource budgets. */
    static AelisCraftingPlanner create(
            int maxNodes,
            int compileBudgetMillis,
            PauseCheckpoint pauseCheckpoint,
            ProgressListener progressListener) {
        return new AelisCraftingPlannerImpl(
                Math.max(1, maxNodes),
                Math.max(1, compileBudgetMillis),
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
        return new AelisCraftingPlannerImpl(
                Math.max(1, maxNodes),
                Math.max(1, compileBudgetMillis),
                pauseCheckpoint == null ? NO_PAUSE : pauseCheckpoint,
                progressListener == null ? ProgressListener.NONE : progressListener,
                craftingService);
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
     *
     * <p>Recoverable runtime and linkage failures are returned as fallback
     * results with an error after attempt-state restoration. Interruption and
     * other errors restore that state and propagate. Optional progress callback
     * failures use the same contract; callbacks run in the calculation context.</p>
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

        /** Stable category for integrations; use fallbackReason for diagnostics. */
        public AelisFallbackReason fallbackCategory() {
            if (applied || branchFailure != null) return AelisFallbackReason.NONE;
            if (error != null) return AelisFallbackReason.INTERNAL_ERROR;
            if (fallbackReason == null || fallbackReason.isBlank()) return AelisFallbackReason.OTHER;
            return AelisFallbackReason.fromDetail(fallbackReason);
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
        try (var scope = CraftingPlannerIntervention.openExplicit()) {
            return tryExecuteScoped(root, inventory, requestedAmount, simulation, missingItems);
        }
    }

    private Result tryExecuteScoped(CraftingTreeNode root, CraftingSimulationState inventory,
            long requestedAmount, boolean simulation, KeyCounter missingItems) throws InterruptedException {
        if (root == null || inventory == null || missingItems == null) {
            return fallback("invalid_planning_context", null);
        }
        if (!(inventory instanceof com.github.appliedenhancements.integration.ae2.AelisCyclicCraftingTracker)
                || !(inventory instanceof com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftingTracker)) {
            return fallback("simulation_state_integration_unavailable", null);
        }

        var missingSnapshot = new KeyCounter();
        missingSnapshot.addAll(missingItems);
        IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot;
        try { possibleSnapshot = snapshotPossibleStates(root); }
        catch (RuntimeException | LinkageError unavailable) {
            return fallback("integration_snapshot_unavailable", unavailable);
        }

        AelisPlanner.Result result;
        try {
            result = delegate.tryExecute(
                    root, inventory, requestedAmount, simulation, missingItems);
        } catch (RuntimeException | LinkageError failure) {
            restoreAttemptState(root, missingItems, missingSnapshot, possibleSnapshot);
            return fallback("internal_execution_exception", failure);
        } catch (InterruptedException | Error failure) {
            restoreAttemptState(root, missingItems, missingSnapshot, possibleSnapshot);
            throw failure;
        }
        if (!result.applied()) {
            restoreAttemptState(root, missingItems, missingSnapshot, possibleSnapshot);
        }
        if (result.applied() && inventory instanceof com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier path) {
            path.molecularmanipulator$setCalculationPath(com.github.appliedenhancements.integration.ae2.AelisCalculationPath.AELIS);
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

    private static Result fallback(String reason, Throwable error) {
        return new Result(false, reason, 0, 0, 0, 0, 0, 0, false, null, error);
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
            if (!(node instanceof AelisCraftingTreeNodeBridge bridge)) continue;
            var processes = bridge.molecularmanipulator$getProcesses();
            if (processes == null) {
                continue;
            }
            for (CraftingTreeProcess process : processes) {
                if (!(process instanceof AelisCraftingTreeProcessBridge processBridge)) continue;
                visitor.accept(process);
                var children = processBridge.molecularmanipulator$getChildNodes();
                if (children != null) {
                    pending.addAll(children.keySet());
                }
            }
        }
    }
}
