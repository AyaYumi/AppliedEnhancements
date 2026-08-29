package com.github.appliedenhancements.crafting.maxfast;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.github.appliedenhancements.crafting.MolecularReusableInputAdapters;
import com.github.appliedenhancements.integration.ae2.OmniCraftingTreeNodeBridge;
import com.github.appliedenhancements.integration.ae2.OmniCraftingTreeProcessBridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class OmniMaxFastPlanner {
    private static final long MAX_LINEAR_ORDERED_NATIVE_ITEMS = 1_000_000L;
    private static final long MAX_LINEAR_NATIVE_BOUNDARY_ITEMS = 8_192L;
    private static final long MAX_SIMULATION_ORDERED_REPLAY_STEPS = 64L;
    private static final String ADVANCED_AE_PROCESSING_PATTERN =
            "net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern";
    private static final String AE2LT_OVERLOAD_PATTERN =
            "com.moakiee.ae2lt.overload.pattern.Ae2OverloadPatternDetails";
    private OmniMaxFastPlanner() {
    }

    @FunctionalInterface
    public interface PauseCheckpoint {
        void pause() throws InterruptedException;
    }

    public interface ProgressSink {
        ProgressSink NONE = new ProgressSink() {
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

    public static final class Session {
        private final int maxNodes;
        private final long compileBudgetNanos;
        private final PauseCheckpoint pauseCheckpoint;
        private final ProgressSink progressSink;
        private CraftingTreeNode root;
        private Graph graph;
        private String structuralFailure;
        private String transactionalRuntimeFailure;
        private Throwable structuralError;
        private long compileNanos;

        public Session(int maxNodes, int compileBudgetMillis,
                PauseCheckpoint pauseCheckpoint) {
            this(maxNodes, compileBudgetMillis, pauseCheckpoint, ProgressSink.NONE);
        }

        public Session(int maxNodes, int compileBudgetMillis,
                PauseCheckpoint pauseCheckpoint, ProgressSink progressSink) {
            this.maxNodes = maxNodes;
            this.compileBudgetNanos = TimeUnit.MILLISECONDS.toNanos(compileBudgetMillis);
            this.pauseCheckpoint = pauseCheckpoint;
            this.progressSink = progressSink == null ? ProgressSink.NONE : progressSink;
        }

        public Result tryExecute(CraftingTreeNode requestedRoot, CraftingSimulationState inventory,
                long requestedAmount, boolean simulation, KeyCounter missingItems)
                throws InterruptedException {
            if (requestedAmount <= 0) {
                return Result.fallback("invalid_request_amount", 0, 0, 0, 0, 0, null);
            }
            if (root != null && root != requestedRoot) {
                return Result.fallback("calculation_root_changed", 0, 0, 0, 0, 0, null);
            }
            root = requestedRoot;

            if (transactionalRuntimeFailure != null) {
                return Result.fallback(transactionalRuntimeFailure, 0, 0,
                        0, compileNanos, 0, null);
            }

            if (graph == null && structuralFailure == null) {
                progressSink.compilationStarted();
                long startedAt = System.nanoTime();
                long compileDeadline = saturatedAdd(startedAt, compileBudgetNanos);
                long pausedNanos = 0;
                var contextSplitKeys = new HashSet<AEKey>();
                try {
                    while (graph == null && structuralFailure == null) {
                        var compiler = new Compiler(maxNodes, compileDeadline,
                                pauseCheckpoint, Set.copyOf(contextSplitKeys), progressSink);
                        try {
                            graph = compiler.compile(requestedRoot);
                        } catch (ContextSplit split) {
                            // The existing node and compile-time budgets already
                            // bound retries. Do not impose an unrelated 64-key
                            // ceiling on otherwise stable contextual graphs.
                            int splitLimit = maxNodes;
                            boolean changed = false;
                            if (contextSplitKeys.size() < splitLimit) {
                                changed = contextSplitKeys.add(split.triggerKey);
                                for (AEKey key : split.keys) {
                                    if (contextSplitKeys.size() >= splitLimit) {
                                        break;
                                    }
                                    changed |= contextSplitKeys.add(key);
                                }
                            }
                            if (!changed) {
                                structuralFailure = contextSplitKeys.size() >= splitLimit
                                        ? "context_split_limit"
                                        : "context_split_unstable:" + split.reason;
                            } else if (Config.MAX_FAST_DIAGNOSTICS.get()) {
                                AppliedEnhancements.LOGGER.info(
                                        "Omni MAX_FAST context split retry: key={}, reason={}, splitKeys={}",
                                        split.triggerKey, split.reason,
                                        contextSplitKeys.size());
                            }
                        } catch (Fallback fallback) {
                            structuralFailure = fallback.reason;
                        } catch (RuntimeException exception) {
                            structuralFailure = "internal_compile_exception";
                            structuralError = exception;
                        } finally {
                            compileDeadline = compiler.deadline;
                            pausedNanos = saturatedAdd(pausedNanos, compiler.pausedNanos);
                        }
                    }
                } finally {
                    compileNanos = Math.max(0,
                            System.nanoTime() - startedAt - pausedNanos);
                }
            }

            if (graph == null) {
                return Result.fallback(structuralFailure, 0, 0, 0,
                        compileNanos, 0, structuralError);
            }
            String executionSafetyFailure = graph.executionSafetyFailure();
            if (executionSafetyFailure != null) {
                transactionalRuntimeFailure = executionSafetyFailure;
                return Result.fallback(transactionalRuntimeFailure, graph.nodes.size(),
                        graph.mergedOccurrences, graph.barrierCount,
                        compileNanos, 0, null);
            }

            long startedAt = System.nanoTime();
            try {
                boolean contextualExecution = graph.executionScope()
                        == OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL;
                long executionTotal = contextualExecution
                        ? -1
                        : graph.topologicalOrder.length;
                progressSink.executionStarted(executionTotal);
                execute(graph, inventory, requestedAmount, simulation, missingItems,
                        pauseCheckpoint, progressSink);
                return Result.applied(graph.nodes.size(), graph.mergedOccurrences, graph.barrierCount,
                        graph.logicalNodeCount, graph.requiresNativeNodeCount(),
                        compileNanos, System.nanoTime() - startedAt);
            } catch (CraftBranchFailure failure) {
                if (graph.hasOrderedChoices()) {
                    AppliedEnhancements.LOGGER.warn(
                            "Omni MAX_FAST CraftBranchFailure treated as hard failure: {}",
                            failure.getMessage());
                    return Result.branchFailure(graph.nodes.size(), graph.mergedOccurrences,
                            graph.barrierCount, compileNanos,
                            System.nanoTime() - startedAt, failure);
                }
                // Reusable-only graphs have no later recipe candidate whose
                // result AE2 could change. Propagate the exact branch failure
                // directly and avoid repeating the same deterministic tree.
                return Result.branchFailure(graph.nodes.size(), graph.mergedOccurrences,
                        graph.barrierCount, compileNanos, System.nanoTime() - startedAt, failure);
            } catch (Fallback fallback) {
                if (graph.executionScope()
                        == OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL) {
                    transactionalRuntimeFailure = "transactional_graph_" + fallback.reason;
                }
                return Result.fallback(fallback.reason, graph.nodes.size(), graph.mergedOccurrences,
                        graph.barrierCount,
                        compileNanos, System.nanoTime() - startedAt, null);
            } catch (OmniOrderedChoicePlanningRejectedException rejection) {
                // This is an intentional terminal planning result. Turning it
                // into Result.fallback would immediately re-enter AE2's
                // unbounded per-item ordered-choice loop.
                throw rejection;
            } catch (RuntimeException exception) {
                if (graph.executionScope()
                        == OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL) {
                    transactionalRuntimeFailure =
                            "transactional_graph_internal_execution_exception";
                }
                return Result.fallback("internal_execution_exception", graph.nodes.size(),
                        graph.mergedOccurrences, graph.barrierCount,
                        compileNanos, System.nanoTime() - startedAt, exception);
            }
        }
    }

    public record Result(boolean applied, String fallbackReason, int uniqueNodes,
            long mergedOccurrences, int barrierCount, long logicalNodeCount, long compileNanos,
            long executionNanos, boolean nativeNodeCount,
            CraftBranchFailure branchFailure, Throwable error) {
        private static Result applied(int uniqueNodes, long mergedOccurrences, int barrierCount,
                long logicalNodeCount, boolean nativeNodeCount,
                long compileNanos, long executionNanos) {
            return new Result(true, null, uniqueNodes, mergedOccurrences, barrierCount,
                    logicalNodeCount, compileNanos, executionNanos, nativeNodeCount, null, null);
        }

        private static Result branchFailure(int uniqueNodes, long mergedOccurrences, int barrierCount,
                long compileNanos, long executionNanos, CraftBranchFailure failure) {
            return new Result(false, null, uniqueNodes, mergedOccurrences, barrierCount,
                    0, compileNanos, executionNanos, false, failure, null);
        }

        private static Result fallback(String reason, int uniqueNodes, long mergedOccurrences,
                int barrierCount,
                long compileNanos, long executionNanos, Throwable error) {
            return new Result(false, reason, uniqueNodes, mergedOccurrences, barrierCount,
                    0, compileNanos, executionNanos, false, null, error);
        }
    }

    private static void execute(Graph graph, CraftingSimulationState parent,
            long requestedAmount, boolean simulation, KeyCounter missingItems,
            PauseCheckpoint pauseCheckpoint, ProgressSink progressSink)
            throws Fallback, CraftBranchFailure, InterruptedException {
        var inventory = new ChildCraftingSimulationState(parent);
        if (graph.executionScope()
                == OmniMaxFastExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL) {
            var stagedMissing = new KeyCounter();
            executeTransactionalNode(
                    graph, graph.rootIndex, inventory, requestedAmount,
                    requestedAmount, simulation, stagedMissing,
                    pauseCheckpoint, progressSink);
            inventory.applyDiff(parent);
            missingItems.addAll(stagedMissing);
            return;
        }
        var requests = new long[graph.nodes.size()];
        var stagedMissing = new KeyCounter();
        requests[graph.rootIndex] = requestedAmount;

        for (int nodeIndex : graph.topologicalOrder) {
            checkpoint(pauseCheckpoint);
            long requestMultipliers = requests[nodeIndex];
            if (requestMultipliers <= 0) {
                progressSink.executionStep();
                continue;
            }

            Node node = graph.nodes.get(nodeIndex);

            // Hybrid barrier execution: upstream nodes aggregate, barrier node calls AE2
            if (node.executionMode == ExecutionMode.HYBRID_BARRIER) {
                if (usesCompiledCandidateTrial(node)) {
                    if (tryExecuteCompiledCandidates(
                            graph, nodeIndex, inventory, requestMultipliers,
                            requestedAmount, simulation, stagedMissing, pauseCheckpoint,
                            "topological", null)) {
                        progressSink.executionStep();
                        continue;
                    }
                    enforceOrderedChoiceNativeLimit(
                            graph, node, requestMultipliers, requestedAmount,
                            "topological");
                }
                if (isReusableBoundaryReason(node.barrierReason)
                        && tryExecuteReusableContainerBoundary(
                        node, inventory, requestMultipliers, pauseCheckpoint)) {
                    progressSink.executionStep();
                    continue;
                }
                enforceOrderedChoiceNativeLimit(
                        graph, node, requestMultipliers, requestedAmount,
                        "topological");
                // Call AE2 bridge with aggregated amount from upstream
                executeNativeBoundary(node, inventory, requestMultipliers, "topological");

                progressSink.executionStep();
                continue;
            }

            if (node.barrier) {
                // Legacy path for barriers that couldn't be upgraded to hybrid
                if (isReusableBoundaryReason(node.barrierReason)
                        && tryExecuteReusableContainerBoundary(
                        node, inventory, requestMultipliers, pauseCheckpoint)) {
                    progressSink.executionStep();
                    continue;
                }
                enforceOrderedChoiceNativeLimit(
                        graph, node, requestMultipliers, requestedAmount,
                        "legacy");
                executeNativeBoundary(node, inventory, requestMultipliers, "legacy");
                progressSink.executionStep();
                continue;
            }
            validateTemplates(node, inventory, pauseCheckpoint);
            long requestedItems = checkedMultiply(node.amount, requestMultipliers,
                    "request_amount_overflow");
            inventory.addStackBytes(node.key, node.amount, requestMultipliers);

            long available = inventory.extract(node.key, requestedItems, Actionable.SIMULATE);
            long extractedMultipliers = Math.min(requestMultipliers, available / node.amount);
            if (extractedMultipliers > 0) {
                long extractedAmount = node.amount * extractedMultipliers;
                long extracted = inventory.extract(node.key, extractedAmount, Actionable.MODULATE);
                if (extracted != extractedAmount) {
                    throw new IllegalStateException("Crafting simulation inventory changed during exact extraction");
                }
            }

            long remainingMultipliers = requestMultipliers - extractedMultipliers;
            if (remainingMultipliers == 0) {
                progressSink.executionStep();
                continue;
            }

            long totalRequestedItems = checkedMultiply(node.amount, remainingMultipliers,
                    "remaining_request_overflow");
            if (node.emitter) {
                inventory.emitItems(node.key, totalRequestedItems);
                progressSink.executionStep();
                continue;
            }
            if (node.terminal) {
                if (!simulation) {
                    AppliedEnhancements.LOGGER.warn("Omni MAX_FAST terminal node shortage: key={}, requested={}",
                            node.key, totalRequestedItems);
                    throw new CraftBranchFailure(node.key, totalRequestedItems);
                }
                // AE2 records an exact terminal shortfall during the simulated
                // attempt and then lets parent patterns continue building the
                // plan. Stage it until the entire aggregated graph succeeds so
                // a later fallback cannot leak or duplicate missing entries.
                stagedMissing.add(node.key, totalRequestedItems);
                progressSink.executionStep();
                continue;
            }

            long effectiveOutputPerPattern = node.outputPerPattern;
            if (effectiveOutputPerPattern <= 0) {
                AppliedEnhancements.LOGGER.warn(
                        "Omni MAX_FAST rejected invalid outputPerPattern: key={}, amount={}, barrier={}, executionMode={}, logicalOccurrences={}",
                        node.key, node.amount, node.barrier,
                        node.executionMode, node.logicalOccurrences);
                throw new Fallback("invalid_output_per_pattern");
            }
            long patternTimes = ceilDiv(totalRequestedItems, effectiveOutputPerPattern);
            for (Edge edge : node.edges) {
                long childRequests = checkedMultiply(edge.requestMultiplier, patternTimes,
                        "child_request_overflow");
                requests[edge.childIndex] = checkedAdd(
                        requests[edge.childIndex], childRequests,
                        "merged_request_overflow");
            }

            long remainder = totalRequestedItems % effectiveOutputPerPattern;
            long surplus = remainder == 0 ? 0 : effectiveOutputPerPattern - remainder;
            if (surplus > 0) {
                inventory.insert(node.key, surplus, Actionable.MODULATE);
            }
            inventory.addCrafting(node.details, patternTimes);
            inventory.addBytes(patternTimes);
            progressSink.executionStep();
        }

        inventory.applyDiff(parent);
        missingItems.addAll(stagedMissing);
    }

    private static void executeTransactionalNode(Graph graph, int nodeIndex,
            CraftingSimulationState inventory, long requestMultipliers,
            long rootRequestedAmount, boolean simulation, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, ProgressSink progressSink)
            throws Fallback, CraftBranchFailure, InterruptedException {
        executeTransactionalNode(
                graph, nodeIndex, inventory, requestMultipliers,
                rootRequestedAmount, simulation, stagedMissing,
                pauseCheckpoint, progressSink,
                -1, null, null, null);
    }

    private static void executeTransactionalNode(Graph graph, int nodeIndex,
            CraftingSimulationState inventory, long requestMultipliers,
            long rootRequestedAmount, boolean simulation, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, ProgressSink progressSink,
            int compiledBoundaryIndex, CompiledCandidate compiledCandidate,
            GraphConsumableInput requestInput,
            RuntimeQuantityStockGuard runtimeQuantityGuard)
            throws Fallback, CraftBranchFailure, InterruptedException {
        if (!OmniMaxFastRecursionGuard.tryEnterTransactional()) {
            throw new Fallback("transactional_depth_limit");
        }
        try {
        checkpoint(pauseCheckpoint);
        if (requestMultipliers <= 0) {
            return;
        }
        progressSink.executionStep();

        Node node = graph.nodes.get(nodeIndex);
        boolean executeCompiledBoundary = nodeIndex == compiledBoundaryIndex;
        if (!executeCompiledBoundary
                && (node.barrier || node.executionMode == ExecutionMode.HYBRID_BARRIER)) {
            if (usesCompiledCandidateTrial(node)) {
                if (tryExecuteCompiledCandidates(
                        graph, nodeIndex, inventory, requestMultipliers,
                        rootRequestedAmount, simulation, stagedMissing, pauseCheckpoint,
                        "contextual", requestInput)) {
                    return;
                }
                enforceOrderedChoiceNativeLimit(
                        graph, node, requestMultipliers, rootRequestedAmount,
                        "contextual");
            }
            if (tryExecuteRuntimeQuantityFeedbackBoundary(
                    graph, nodeIndex, inventory, requestMultipliers,
                    rootRequestedAmount, simulation, stagedMissing,
                    pauseCheckpoint, requestInput)) {
                return;
            }
            if ((requestInput == null || !requestInput.substituteInput)
                    && isReusableBoundaryReason(node.barrierReason)
                    && tryExecuteReusableContainerBoundary(
                            node, inventory, requestMultipliers, pauseCheckpoint)) {
                return;
            }

            enforceOrderedChoiceNativeLimit(
                    graph, node, requestMultipliers, rootRequestedAmount,
                    "contextual");
            executeNativeBoundary(
                    node, inventory, requestMultipliers, "contextual", requestInput);
            return;
        }

        long requestedItems = checkedMultiply(
                node.amount, requestMultipliers, "request_amount_overflow");
        inventory.addStackBytes(node.key, node.amount, requestMultipliers);

        long remainingMultipliers;
        if (requestInput != null) {
            remainingMultipliers = extractConsumableInputTemplates(
                    node, inventory, requestInput, requestMultipliers, requestedItems,
                    pauseCheckpoint);
        } else {
            validateTemplates(node, inventory, pauseCheckpoint);
            long available = inventory.extract(
                    node.key, requestedItems, Actionable.SIMULATE);
            long extractedMultipliers = Math.min(
                    requestMultipliers, available / node.amount);
            if (extractedMultipliers > 0) {
                long extractedAmount = node.amount * extractedMultipliers;
                long extracted = inventory.extract(
                        node.key, extractedAmount, Actionable.MODULATE);
                if (extracted != extractedAmount) {
                    throw new IllegalStateException(
                            "Crafting simulation inventory changed during exact extraction");
                }
            }
            remainingMultipliers = requestMultipliers - extractedMultipliers;
        }

        if (remainingMultipliers == 0) {
            return;
        }
        long totalRequestedItems = checkedMultiply(
                node.amount, remainingMultipliers, "remaining_request_overflow");
        if (node.emitter) {
            inventory.emitItems(node.key, totalRequestedItems);
            return;
        }
        if (node.terminal) {
            if (!simulation) {
                throw new CraftBranchFailure(node.key, totalRequestedItems);
            }
            if (stagedMissing == null) {
                throw new Fallback("missing_terminal_input");
            }
            stagedMissing.add(node.key, totalRequestedItems);
            return;
        }

        long effectiveOutputPerPattern = executeCompiledBoundary
                ? compiledCandidate.outputPerPattern
                : node.outputPerPattern;
        if (effectiveOutputPerPattern <= 0) {
            AppliedEnhancements.LOGGER.warn(
                    "Omni MAX_FAST rejected invalid transactional outputPerPattern: key={}, amount={}, barrier={}, barrierReason={}, executionMode={}, logicalOccurrences={}",
                    node.key, node.amount, node.barrier, node.barrierReason,
                    node.executionMode, node.logicalOccurrences);
            throw new Fallback("invalid_output_per_pattern");
        }
        long patternTimes = ceilDiv(totalRequestedItems, effectiveOutputPerPattern);
        if (runtimeQuantityGuard != null) {
            validateRuntimeQuantityFeedbackStock(
                    graph, node, compiledCandidate, inventory,
                    remainingMultipliers, patternTimes, pauseCheckpoint,
                    runtimeQuantityGuard);
        }
        var returnedReusableInputs = new KeyCounter();
        List<OrderedGraphInput> selectedInputs = executeCompiledBoundary
                ? compiledCandidate.orderedInputs
                : node.orderedInputs;
        for (OrderedGraphInput orderedInput : selectedInputs) {
            checkpoint(pauseCheckpoint);
            if (orderedInput.reusable()) {
                GraphReusableInput reusableInput = orderedInput.reusableInput;
                if (reusableInput.mode != BoundaryInputMode.INVARIANT_REUSABLE
                        || !leaseInvariantReusableInput(
                                inventory, reusableInput, patternTimes,
                                returnedReusableInputs, pauseCheckpoint)) {
                    throw new Fallback("reusable_input_unavailable");
                }
            } else {
                GraphConsumableInput consumableInput = orderedInput.consumableInput;
                long childRequests = checkedMultiply(
                        consumableInput.multiplier, patternTimes,
                        "child_request_overflow");
                executeTransactionalNode(
                        graph, consumableInput.childIndex, inventory,
                        childRequests, rootRequestedAmount, simulation,
                        stagedMissing, pauseCheckpoint,
                        progressSink, -1, null, consumableInput, null);
            }
        }

        for (var stack : returnedReusableInputs) {
            inventory.insert(stack.getKey(), stack.getLongValue(), Actionable.MODULATE);
            long logicalReturns = checkedMultiply(
                    stack.getLongValue(), patternTimes,
                    "reusable_return_bytes_overflow");
            inventory.addStackBytes(stack.getKey(), 1, logicalReturns);
        }

        long remainder = totalRequestedItems % effectiveOutputPerPattern;
        long surplus = remainder == 0 ? 0 : effectiveOutputPerPattern - remainder;
        if (surplus > 0) {
            inventory.insert(node.key, surplus, Actionable.MODULATE);
        }
        inventory.addCrafting(
                executeCompiledBoundary ? compiledCandidate.details : node.details,
                patternTimes);
        inventory.addBytes(patternTimes);
        } finally {
            OmniMaxFastRecursionGuard.exitTransactional();
        }
    }

    /**
     * A failed compile-time descendant-isolation proof does not always require
     * AE2's per-craft quantity loop. Once the owner item has been extracted, a
     * complete direct-stock check proves that none of the non-feedback child
     * recipes can run. The already compiled transaction is then equivalent to
     * the native sequence, while reducing an O(pattern count) loop to one batch.
     *
     * <p>The speculative execution is isolated in a child state. A changed
     * template, overflow or missing direct input therefore leaves the parent
     * untouched before the caller executes the native boundary exactly once.</p>
     */
    private static boolean tryExecuteRuntimeQuantityFeedbackBoundary(
            Graph graph, int nodeIndex, CraftingSimulationState parent,
            long requestMultipliers, long rootRequestedAmount, boolean simulation,
            KeyCounter stagedMissing, PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput)
            throws CraftBranchFailure, InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        if (!"quantity_feedback_descendant_unsafe".equals(node.barrierReason)
                || node.logicalOccurrences != 1
                || !node.allCandidatesCompiled
                || node.candidatePatterns.size() != 1
                || node.compiledCandidates.size() != 1
                || stagedMissing == null
                || requestInput != null && requestInput.substituteInput) {
            return false;
        }

        CompiledCandidate candidate = node.compiledCandidates.getFirst();
        if (!candidate.quantityFeedbackBatch) {
            return false;
        }

        var attemptInventory = new ChildCraftingSimulationState(parent);
        var attemptMissing = new KeyCounter();
        var runtimeGuard = new RuntimeQuantityStockGuard();
        try {
            executeTransactionalNode(
                    graph, nodeIndex, attemptInventory, requestMultipliers,
                    rootRequestedAmount, simulation, attemptMissing, pauseCheckpoint,
                    ProgressSink.NONE, nodeIndex, candidate, requestInput,
                    runtimeGuard);
            validateKeyCounterMerge(
                    stagedMissing, attemptMissing,
                    "quantity_feedback_missing_overflow");
        } catch (Fallback fallback) {
            if (Config.MAX_FAST_DIAGNOSTICS.get()) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST runtime quantity batch delegated to native: key={}, amount={}, aggregatedRequest={}, reason={}",
                        node.key, node.amount, requestMultipliers, fallback.reason);
            }
            return false;
        }

        attemptInventory.applyDiff(parent);
        stagedMissing.addAll(attemptMissing);
        if (Config.MAX_FAST_DIAGNOSTICS.get()
                || requestMultipliers >= 1_000_000) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST runtime inventory-isolated quantity batch: key={}, amount={}, aggregatedRequest={}, patterns={}, stockedInputKeys={}",
                    node.key, node.amount, requestMultipliers,
                    runtimeGuard.patternTimes, runtimeGuard.stockedInputKeys);
        }
        return true;
    }

    private static void validateKeyCounterMerge(
            KeyCounter target, KeyCounter addition, String reason)
            throws Fallback {
        for (var stack : addition) {
            checkedAdd(target.get(stack.getKey()), stack.getLongValue(), reason);
        }
    }

    private static void validateRuntimeQuantityFeedbackStock(
            Graph graph, Node owner, CompiledCandidate candidate,
            CraftingSimulationState inventory, long remainingMultipliers,
            long expectedPatternTimes, PauseCheckpoint pauseCheckpoint,
            RuntimeQuantityStockGuard guard)
            throws Fallback, InterruptedException {
        if (candidate == null || !candidate.quantityFeedbackBatch
                || !candidate.limitsQuantity || owner.amount != 1
                || candidate.hasContainerItems
                || candidate.orderedInputs.isEmpty()) {
            throw new Fallback("quantity_feedback_runtime_shape");
        }

        var inputs = new ArrayList<OmniQuantityFeedbackRuntimeBatch.Input<AEKey>>(
                candidate.orderedInputs.size());
        for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
            if (orderedInput.reusable()) {
                throw new Fallback("quantity_feedback_runtime_reusable_input");
            }
            GraphConsumableInput consumable = orderedInput.consumableInput;
            if (consumable == null || consumable.substituteInput
                    || consumable.multiplier <= 0
                    || consumable.childIndex < 0
                    || consumable.childIndex >= graph.nodes.size()) {
                throw new Fallback("quantity_feedback_runtime_input");
            }
            Node child = graph.nodes.get(consumable.childIndex);
            boolean feedback = owner.key.equals(child.key);
            if (feedback && (!child.terminal || child.emitter)) {
                throw new Fallback("quantity_feedback_runtime_feedback_node");
            }
            inputs.add(new OmniQuantityFeedbackRuntimeBatch.Input<>(
                    child.key, child.amount, consumable.multiplier, feedback));
        }

        var plan = OmniQuantityFeedbackRuntimeBatch.plan(
                owner.amount, remainingMultipliers,
                candidate.outputPerPattern, inputs)
                .orElseThrow(() -> new Fallback(
                        "quantity_feedback_runtime_overflow"));
        if (plan.patternTimes() != expectedPatternTimes) {
            throw new Fallback("quantity_feedback_runtime_pattern_mismatch");
        }
        if (plan.feedbackItems() <= 0
                || plan.feedbackItems() % plan.patternTimes() != 0
                || candidate.outputPerPattern
                        <= plan.feedbackItems() / plan.patternTimes()) {
            throw new Fallback("quantity_feedback_runtime_non_positive_net_output");
        }

        for (var requirement : plan.nonFeedbackRequirements().entrySet()) {
            checkpoint(pauseCheckpoint);
            long required = requirement.getValue();
            if (required <= 0 || inventory.extract(
                    requirement.getKey(), required, Actionable.SIMULATE) < required) {
                throw new Fallback("quantity_feedback_direct_stock_shortage");
            }
        }
        guard.patternTimes = plan.patternTimes();
        guard.stockedInputKeys = plan.nonFeedbackRequirements().size();
    }

    private static boolean usesCompiledCandidateTrial(Node node) {
        return OmniMaxFastExecutionPolicy.selectBoundary(node.barrierReason)
                == OmniMaxFastExecutionPolicy.BoundaryStrategy.COMPILED_CANDIDATES_THEN_NATIVE;
    }

    private static void enforceOrderedChoiceNativeLimit(
            Graph graph, Node node, long requestMultipliers,
            long rootRequestedAmount, String path) {
        if (!hasLiveOrderedCandidateChoice(node)) {
            return;
        }
        OmniOrderedChoiceFallback.Decision decision =
                OmniOrderedChoiceFallback.afterCompiledFailure(
                        node.amount, requestMultipliers,
                        rootRequestedAmount,
                        MAX_LINEAR_ORDERED_NATIVE_ITEMS);
        if (decision != OmniOrderedChoiceFallback.Decision.CONTROLLED_REJECT) {
            return;
        }

        var rejection = new OmniOrderedChoicePlanningRejectedException(
                node.key, node.amount, requestMultipliers,
                MAX_LINEAR_ORDERED_NATIVE_ITEMS);
        AppliedEnhancements.LOGGER.warn(
                "Omni MAX_FAST rejected unbounded ordered-choice native replay: path={}, key={}, amount={}, aggregatedRequest={}, rootRequest={}, linearItems={}, limit={}",
                path, node.key, node.amount, requestMultipliers,
                rootRequestedAmount, rejection.requestedItems(),
                rejection.maxLinearNativeItems());
        throw rejection;
    }

    private static boolean hasLiveOrderedCandidateChoice(Node node) {
        int candidateCount = node.candidatePatterns.size();
        try {
            if (!node.occurrences.isEmpty()) {
                List<CraftingTreeProcess> processes =
                        ((OmniCraftingTreeNodeBridge) node.occurrences.getFirst())
                                .molecularmanipulator$getProcesses();
                if (processes != null) {
                    candidateCount = Math.max(candidateCount, processes.size());
                }
            }
        } catch (RuntimeException exception) {
            // The compiler snapshot remains a conservative ordered-choice
            // fact when a compatibility bridge cannot expose live processes.
        }
        return OmniMaxFastExecutionPolicy.isOrderedCandidateChoice(
                node.barrierReason, candidateCount);
    }

    /**
     * AE2 executes a multi-pattern node one craft at a time so it can roll back
     * a failed candidate and continue with the next one. Try the already
     * compiled candidates as aggregated child transactions. Each failed branch
     * restores all process flags before the next branch is considered. A real
     * aggressive attempt with every candidate compiled uses binary allocation
     * to preserve AE2's first-candidate-first mixing without crafting one item at
     * a time. When the complete choice is non-deterministic, only a first
     * candidate whose entire subtree is independently batch-valid may be
     * tried once. Compatibility failures still use the native chooser.
     */
    private static boolean tryExecuteCompiledCandidates(Graph graph, int nodeIndex,
            CraftingSimulationState parent, long requestMultipliers,
            long rootRequestedAmount, boolean simulation, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, String path,
            GraphConsumableInput requestInput)
            throws CraftBranchFailure, InterruptedException, Fallback {
        Node node = graph.nodes.get(nodeIndex);
        node.orderedFallbackReason = null;
        node.orderedFallbackDetail = null;
        boolean diagnostics = Config.MAX_FAST_DIAGNOSTICS.get();
        boolean certifiedPrefixProbe =
                OmniFirstCandidatePrefixProbeScope.active();
        boolean deterministicCandidates = !certifiedPrefixProbe
                && hasDeterministicCandidateSubgraph(graph, node);
        CraftingTreeNode candidateRoot = node.occurrences.isEmpty()
                ? null
                : node.occurrences.getFirst();
        CandidateOccurrenceCheck entryCandidateCheck =
                inspectSimulationFirstCandidateOccurrence(
                        node, candidateRoot, true, true, simulation);
        if (!entryCandidateCheck.safe) {
            if (certifiedPrefixProbe) {
                throw new CertifiedPrefixProbeFallback(
                        "stale_or_impossible_first_candidate");
            }
            if (diagnostics) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST ordered choice delegated directly to native: path={}, key={}, amount={}, aggregatedRequest={}, reason=stale_or_impossible_first_candidate, detail={}",
                        path, node.key, node.amount, requestMultipliers,
                        entryCandidateCheck.detail);
            }
            node.orderedFallbackReason =
                    "stale_or_impossible_first_candidate";
            node.orderedFallbackDetail = entryCandidateCheck.detail;
            return false;
        }
        boolean exactRequest = requestInput == null || !requestInput.substituteInput;
        boolean firstCandidateBatchValid = !certifiedPrefixProbe
                && exactRequest
                && hasBatchValidFirstCandidateSubgraph(graph, node);
        SimulationFirstCandidateProof simulationProof = simulation
                ? certifySimulationFirstCandidate(
                        graph, nodeIndex, parent, requestInput,
                        pauseCheckpoint)
                : SimulationFirstCandidateProof.accept(0);
        CompiledCandidate firstCandidate = node.compiledCandidates.getFirst();
        boolean singlePatternSimulationShape = simulation
                && exactRequest
                && !simulationProof.safe
                && isLiveSimulationRequestedOccurrence(
                        node, requestInput, candidateRoot)
                && isKnownDeterministicPattern(firstCandidate.details)
                && hasOnlyExactPrimaryOutputs(node, firstCandidate)
                && hasExactStatelessCandidateInputs(firstCandidate)
                && validateSparseRootTemplates(
                        node, parent, requestInput, pauseCheckpoint);
        boolean singlePatternSimulation = singlePatternSimulationShape
                && OmniSimulationSinglePattern.isAtMostOnePattern(
                        node.amount, requestMultipliers,
                        firstCandidate.outputPerPattern);
        boolean boundedPatternReplay = singlePatternSimulationShape
                && !singlePatternSimulation
                && OmniSimulationSinglePattern.canReplayWithin(
                        node.amount, requestMultipliers,
                        firstCandidate.outputPerPattern,
                        MAX_SIMULATION_ORDERED_REPLAY_STEPS);
        boolean simulationCandidateBatchValid = !simulation
                || exactRequest && (simulationProof.safe
                        || singlePatternSimulation);
        if (!OmniOrderedChoiceFallback.mayCommitCompiledResult(
                simulation, simulationCandidateBatchValid)) {
            if (singlePatternSimulationShape
                    && tryExecuteSparseSimulationFirstCandidate(
                            graph, nodeIndex, firstCandidate, parent,
                            requestMultipliers, rootRequestedAmount,
                            stagedMissing, pauseCheckpoint, path, requestInput,
                            candidateRoot, diagnostics)) {
                return true;
            }
            if (boundedPatternReplay
                    && tryExecuteSimulationFirstCandidateLinearly(
                            graph, nodeIndex, firstCandidate, parent,
                            requestMultipliers, rootRequestedAmount,
                            stagedMissing, pauseCheckpoint, path, requestInput,
                            candidateRoot, diagnostics)) {
                return true;
            }
            long replaySteps = singlePatternSimulationShape
                    ? OmniSimulationSinglePattern.requiredReplaySteps(
                            node.amount, requestMultipliers,
                            firstCandidate.outputPerPattern)
                    : 0;
            if (singlePatternSimulationShape
                    && replaySteps > MAX_SIMULATION_ORDERED_REPLAY_STEPS) {
                long maxReplayItems = saturatedMultiply(
                        firstCandidate.outputPerPattern,
                        MAX_SIMULATION_ORDERED_REPLAY_STEPS);
                AppliedEnhancements.LOGGER.warn(
                        "Omni MAX_FAST rejected expensive simulation replay: path={}, key={}, requested={}, estimatedSteps={}, stepLimit={}, certificateReason={}, certificateDetail={}",
                        path, node.key, requestMultipliers, replaySteps,
                        MAX_SIMULATION_ORDERED_REPLAY_STEPS,
                        simulationProof.reason, simulationProof.detail);
                throw new OmniOrderedChoicePlanningRejectedException(
                        node.key, node.amount, requestMultipliers,
                        maxReplayItems);
            }
            String simulationReason = exactRequest
                    ? simulationProof.reason : "substitute_request";
            String simulationDetail = exactRequest
                    ? simulationProof.detail : null;
            if (diagnostics) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST ordered choice delegated directly to native: path={}, key={}, amount={}, aggregatedRequest={}, reason=simulation_candidate_not_batch_certified:{}, detail={}",
                        path, node.key, node.amount, requestMultipliers,
                        simulationReason, simulationDetail);
            } else if (simulation && requestMultipliers >= 1_000) {
                AppliedEnhancements.LOGGER.warn(
                        "Omni MAX_FAST simulation first-candidate certificate rejected: path={}, key={}, amount={}, aggregatedRequest={}, reason={}, detail={}",
                        path, node.key, node.amount, requestMultipliers,
                        simulationReason, simulationDetail);
            }
            if (node.orderedFallbackReason == null) {
                node.orderedFallbackReason =
                        "simulation_candidate_not_batch_certified:"
                                + simulationReason;
                node.orderedFallbackDetail = simulationDetail;
            }
            return false;
        }
        if (diagnostics && singlePatternSimulation) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST simulation single-pattern first candidate certified: path={}, key={}, amount={}, aggregatedRequest={}, outputPerPattern={}",
                    path, node.key, node.amount, requestMultipliers,
                    firstCandidate.outputPerPattern);
        } else if (diagnostics && simulationProof.safe
                && requestMultipliers >= 1_000) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST simulation recursive first-candidate certified: path={}, key={}, aggregatedRequest={}, producedKeys={}",
                    path, node.key, requestMultipliers,
                    simulationProof.producedKeys);
        }
        boolean directStockCandidateSet = !certifiedPrefixProbe
                && exactRequest
                && hasDirectStockCandidateSet(graph, node);
        boolean safeCandidateMix = !certifiedPrefixProbe
                && hasLiveCandidateSet(node, candidateRoot)
                && (deterministicCandidates || directStockCandidateSet);
        boolean batchCandidateMix = !certifiedPrefixProbe
                && OmniMaxFastExecutionPolicy.canBatchCandidateMix(
                        simulation, node.allCandidatesCompiled,
                        node.compiledCandidates.size(), node.candidatePatterns.size(),
                        safeCandidateMix);
        int candidateLimit = certifiedPrefixProbe
                ? 1
                : OmniMaxFastExecutionPolicy.compiledCandidateTrialLimit(
                        simulation, deterministicCandidates,
                        simulation
                                ? simulationCandidateBatchValid
                                : firstCandidateBatchValid,
                        node.compiledCandidates.size());
        if (!batchCandidateMix && candidateLimit == 0) {
            if (diagnostics) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST ordered choice delegated directly to native: path={}, key={}, amount={}, aggregatedRequest={}, reason=unsafe_first_candidate_subgraph",
                        path, node.key, node.amount, requestMultipliers);
            }
            node.orderedFallbackReason = "unsafe_first_candidate_subgraph";
            return false;
        }

        // Snapshotting walks the built AE2 candidate tree. Defer it until a
        // compiled attempt will actually run so direct-native routing remains
        // O(1) in the size of that tree.
        var possibleSnapshot = snapshotPossibleStates(candidateRoot);
        int recoveredCandidateStates = enableSimulationCandidateRecovery(
                simulationProof, possibleSnapshot);
        if (recoveredCandidateStates > 0
                && (diagnostics || requestMultipliers >= 1_000)) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST simulation transient candidate state recovered: path={}, key={}, aggregatedRequest={}, processes={}",
                    path, node.key, requestMultipliers,
                    recoveredCandidateStates);
        }
        if (batchCandidateMix) {
            boolean applied = tryExecuteCompiledCandidateMix(
                    graph, nodeIndex, parent, requestMultipliers,
                    rootRequestedAmount,
                    stagedMissing, pauseCheckpoint, path, requestInput,
                    candidateRoot, possibleSnapshot, diagnostics,
                    !deterministicCandidates && directStockCandidateSet);
            if (!applied) {
                node.orderedFallbackReason =
                        "compiled_candidate_mix_rejected";
            }
            return applied;
        }

        if (candidateLimit == 1) {
            CompiledCandidate candidate = node.compiledCandidates.getFirst();
            CandidateAttempt attempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, parent, requestMultipliers,
                    rootRequestedAmount, simulation, pauseCheckpoint, requestInput,
                    candidateRoot, possibleSnapshot, diagnostics, path);
            preserveRecoveredCandidateStates(
                    attempt, simulationProof, possibleSnapshot);
            if (attempt.status == CandidateAttemptStatus.APPLIED) {
                attempt.inventory.applyDiff(parent);
                stagedMissing.addAll(attempt.missing);
                restorePossibleStates(candidateRoot, attempt.possibleStates);
                return true;
            }
            if (certifiedPrefixProbe) {
                if (attempt.status == CandidateAttemptStatus.SHORTAGE) {
                    throw new CraftBranchFailure(
                            node.key,
                            saturatedMultiply(node.amount, requestMultipliers));
                }
                throw new CertifiedPrefixProbeFallback(
                        "nested_candidate_fallback");
            }
            if (attempt.status == CandidateAttemptStatus.SHORTAGE
                    && OmniMaxFastExecutionPolicy
                            .shouldTrySparseCandidateSetAfterFirstShortage(
                                    simulation, exactRequest,
                                    node.allCandidatesCompiled,
                                    node.compiledCandidates.size(),
                                    node.candidatePatterns.size(),
                                    hasLiveCandidateSet(node, candidateRoot))) {
                boolean applied = tryExecuteCompiledCandidateMix(
                        graph, nodeIndex, parent, requestMultipliers,
                        rootRequestedAmount, stagedMissing, pauseCheckpoint,
                        path + "_sparse_after_shortage", requestInput,
                        candidateRoot, possibleSnapshot, diagnostics, true);
                if (applied) {
                    return true;
                }
            }
            if (attempt.status == CandidateAttemptStatus.SHORTAGE
                    && !simulation
                    && exactRequest
                    && requestMultipliers > 1
                    && tryExecuteNativeAggregateCandidateMix(
                            graph, nodeIndex, parent, requestMultipliers,
                            rootRequestedAmount, pauseCheckpoint, path,
                            requestInput, candidateRoot, diagnostics)) {
                return true;
            }
            if (!simulation
                    && exactRequest
                    && requestMultipliers > 1
                    && attempt.status == CandidateAttemptStatus.SHORTAGE) {
                SimulationFirstCandidateProof prefixProof =
                        certifySimulationFirstCandidate(
                                graph, nodeIndex, parent, requestInput,
                                pauseCheckpoint);
                if (prefixProof.safe && tryExecuteCertifiedFirstCandidatePrefix(
                        graph, nodeIndex, candidate, parent,
                        requestMultipliers, rootRequestedAmount,
                        stagedMissing, pauseCheckpoint, path, requestInput,
                        candidateRoot, possibleSnapshot, diagnostics)) {
                    return true;
                }
            }
            node.orderedFallbackReason = attempt.reason;
            return false;
        }

        var lastFailure = new String[] { "compiled_candidate_rejected" };
        boolean applied = OmniOrderedCandidateTrial.tryInOrder(
                candidateLimit, compiledIndex -> {
            CompiledCandidate candidate = node.compiledCandidates.get(compiledIndex);
            CandidateAttempt attempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, parent, requestMultipliers,
                    rootRequestedAmount, simulation, pauseCheckpoint, requestInput,
                    candidateRoot, possibleSnapshot, diagnostics, path);
            if (attempt.status == CandidateAttemptStatus.APPLIED) {
                attempt.inventory.applyDiff(parent);
                stagedMissing.addAll(attempt.missing);
                restorePossibleStates(candidateRoot, attempt.possibleStates);
                return true;
            }
            lastFailure[0] = attempt.reason;
            return false;
        });
        if (!applied) {
            node.orderedFallbackReason = lastFailure[0];
        }
        return applied;
    }

    /**
     * AE2 deliberately invokes every process of a multi-pattern node one craft
     * at a time. When every live process has a single-path, stateless subtree,
     * invoking that same process with an aggregate pattern count is equivalent.
     * Child transactions and binary capacity probes preserve first-candidate
     * priority without requiring the complete choice to compile.
     */
    private static boolean tryExecuteNativeAggregateCandidateMix(
            Graph graph, int nodeIndex, CraftingSimulationState parent,
            long requestMultipliers, long rootRequestedAmount,
            PauseCheckpoint pauseCheckpoint, String path,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot,
            boolean diagnostics)
            throws CraftBranchFailure, InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        var rejection = new String[1];
        List<CraftingTreeProcess> processes =
                getNativeAggregateCandidateSet(node, candidateRoot, rejection);
        if (processes == null) {
            if (diagnostics || requestMultipliers >= 1_000) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST native aggregate candidate set rejected: path={}, key={}, requested={}, compiledCandidates={}, totalCandidates={}, allCompiled={}, compileFailures={}, reason={}",
                        path, node.key, requestMultipliers,
                        node.compiledCandidates.size(), node.candidatePatterns.size(),
                        node.allCandidatesCompiled, node.candidateCompileFailures,
                        rejection[0]);
            }
            return false;
        }

        var originalPossible = snapshotPossibleStates(candidateRoot);
        var workingPossible = originalPossible;
        var mixedInventory = new ChildCraftingSimulationState(parent);
        long remaining = requestMultipliers;
        int totalProbes = 0;
        var allocations = new long[processes.size()];
        for (int candidateIndex = 0;
                candidateIndex < processes.size() && remaining > 0;
                candidateIndex++) {
            checkpoint(pauseCheckpoint);
            CraftingTreeProcess process = processes.get(candidateIndex);
            var processBridge = (OmniCraftingTreeProcessBridge) process;
            if (!processBridge.molecularmanipulator$isPossible()) {
                continue;
            }

            final long candidateRequest = remaining;
            final IdentityHashMap<CraftingTreeProcess, Boolean> probeSnapshot =
                    workingPossible;
            CandidateAttempt fullAttempt = executeNativeAggregateCandidate(
                    node, process, mixedInventory, candidateRequest,
                    pauseCheckpoint, requestInput, candidateRoot,
                    probeSnapshot, diagnostics, path);
            totalProbes++;
            if (fullAttempt.status == CandidateAttemptStatus.FALLBACK) {
                restorePossibleStates(candidateRoot, originalPossible);
                return false;
            }

            long allocation;
            CandidateAttempt best;
            int candidateProbes;
            if (fullAttempt.status == CandidateAttemptStatus.APPLIED) {
                allocation = candidateRequest;
                best = fullAttempt;
                candidateProbes = 0;
            } else {
                OmniMaximumSuccessfulPrefix.Result<CandidateAttempt> prefix =
                        OmniMaximumSuccessfulPrefix.find(
                            candidateRequest, trialAmount -> {
                                CandidateAttempt trial = executeNativeAggregateCandidate(
                                        node, process, mixedInventory, trialAmount,
                                        pauseCheckpoint, requestInput, candidateRoot,
                                        probeSnapshot, diagnostics, path);
                                return switch (trial.status) {
                                    case APPLIED ->
                                            OmniMaximumSuccessfulPrefix.ProbeResult
                                                    .applied(trial);
                                    case SHORTAGE ->
                                            OmniMaximumSuccessfulPrefix.ProbeResult
                                                    .shortage();
                                    case FALLBACK ->
                                            OmniMaximumSuccessfulPrefix.ProbeResult
                                                    .fallback();
                                };
                            });
                candidateProbes = prefix.probes();
                if (prefix.fallback()) {
                    restorePossibleStates(candidateRoot, originalPossible);
                    return false;
                }
                allocation = prefix.allocation();
                best = prefix.value();
            }
            totalProbes += candidateProbes;
            if (allocation <= 0 || best == null) {
                continue;
            }

            best.inventory.applyDiff(mixedInventory);
            restorePossibleStates(candidateRoot, best.possibleStates);
            workingPossible = best.possibleStates;
            allocations[candidateIndex] = allocation;
            remaining -= allocation;
        }

        if (remaining > 0) {
            restorePossibleStates(candidateRoot, originalPossible);
            throw new CraftBranchFailure(
                    node.key, saturatedMultiply(node.amount, remaining));
        }

        mixedInventory.applyDiff(parent);
        restorePossibleStates(candidateRoot, workingPossible);
        if (diagnostics || requestMultipliers >= 1_000) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST native aggregate candidate mix committed: path={}, key={}, requested={}, allocations={}, probes={}",
                    path, node.key, requestMultipliers,
                    java.util.Arrays.toString(allocations), totalProbes);
        }
        return true;
    }

    private static List<CraftingTreeProcess> getNativeAggregateCandidateSet(
            Node node, CraftingTreeNode candidateRoot,
            String[] rejection) {
        if (candidateRoot == null) {
            rejection[0] = "missing_root";
            return null;
        }
        try {
            List<CraftingTreeProcess> processes =
                    ((OmniCraftingTreeNodeBridge) candidateRoot)
                            .molecularmanipulator$getProcesses();
            processes = withoutNoProgressCandidates(node, processes);
            if (processes == null || processes.size() <= 1
                    || processes.size() != node.candidatePatterns.size()) {
                rejection[0] = "candidate_count";
                return null;
            }
            for (int index = 0; index < processes.size(); index++) {
                CraftingTreeProcess process = processes.get(index);
                var bridge = (OmniCraftingTreeProcessBridge) process;
                if (bridge.molecularmanipulator$getDetails()
                        != node.candidatePatterns.get(index)) {
                    rejection[0] = "details_identity:" + index;
                    return null;
                }
                if (bridge.molecularmanipulator$hasContainerItems()) {
                    rejection[0] = "container_items:" + index;
                    return null;
                }
                if (bridge.molecularmanipulator$limitsQuantity()) {
                    rejection[0] = "quantity_limit:" + index;
                    return null;
                }
                if (bridge.molecularmanipulator$hasMultiplePaths()) {
                    rejection[0] = "nested_multiple_paths:" + index;
                    return null;
                }
                if (getExactCandidateOutputCount(
                        node.key,
                        bridge.molecularmanipulator$getDetails()) <= 0) {
                    rejection[0] = "non_exact_output:" + index;
                    return null;
                }
            }
            return List.copyOf(processes);
        } catch (RuntimeException exception) {
            rejection[0] = "validation_error:"
                    + exception.getClass().getSimpleName();
            return null;
        }
    }

    private static CandidateAttempt executeNativeAggregateCandidate(
            Node node, CraftingTreeProcess process,
            CraftingSimulationState parent, long requestMultipliers,
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot,
            boolean diagnostics, String path)
            throws InterruptedException {
        long startedAt = diagnostics ? System.nanoTime() : 0;
        var candidateInventory = new ChildCraftingSimulationState(parent);
        CandidateAttemptStatus status = CandidateAttemptStatus.FALLBACK;
        String result = "native_aggregate_unknown";
        try {
            checkpoint(pauseCheckpoint);
            long requestedItems = checkedMultiply(
                    node.amount, requestMultipliers,
                    "native_candidate_request_overflow");
            candidateInventory.addStackBytes(
                    node.key, node.amount, requestMultipliers);

            long remainingMultipliers;
            if (requestInput != null) {
                remainingMultipliers = extractConsumableInputTemplates(
                        node, candidateInventory, requestInput,
                        requestMultipliers, requestedItems, pauseCheckpoint);
            } else {
                validateTemplates(node, candidateInventory, pauseCheckpoint);
                long available = candidateInventory.extract(
                        node.key, requestedItems, Actionable.SIMULATE);
                long extractedMultipliers = Math.min(
                        requestMultipliers, available / node.amount);
                if (extractedMultipliers > 0) {
                    long extractedAmount = node.amount * extractedMultipliers;
                    long extracted = candidateInventory.extract(
                            node.key, extractedAmount, Actionable.MODULATE);
                    if (extracted != extractedAmount) {
                        throw new IllegalStateException(
                                "Crafting simulation inventory changed during native aggregate extraction");
                    }
                }
                remainingMultipliers = requestMultipliers - extractedMultipliers;
            }

            if (remainingMultipliers > 0) {
                var bridge = (OmniCraftingTreeProcessBridge) process;
                long outputPerPattern = getExactCandidateOutputCount(
                        node.key, bridge.molecularmanipulator$getDetails());
                if (outputPerPattern <= 0) {
                    result = "native_aggregate_output";
                    return new CandidateAttempt(
                            status, null, null, null, result);
                }
                long remainingItems = checkedMultiply(
                        node.amount, remainingMultipliers,
                        "native_candidate_remaining_overflow");
                long patternTimes = ceilDiv(
                        remainingItems, outputPerPattern);
                bridge.molecularmanipulator$request(
                        candidateInventory, patternTimes);
                long extracted = candidateInventory.extract(
                        node.key, remainingItems, Actionable.MODULATE);
                if (extracted != remainingItems) {
                    result = "native_aggregate_output_mismatch";
                    return new CandidateAttempt(
                            status, null, null, null, result);
                }
            }

            status = CandidateAttemptStatus.APPLIED;
            result = "native_aggregate_applied";
            return new CandidateAttempt(
                    status, candidateInventory, new KeyCounter(),
                    snapshotPossibleStates(candidateRoot), result);
        } catch (CraftBranchFailure failure) {
            status = CandidateAttemptStatus.SHORTAGE;
            result = "native_aggregate_shortage";
            return new CandidateAttempt(status, null, null, null, result);
        } catch (Fallback fallback) {
            result = fallback.reason;
            return new CandidateAttempt(status, null, null, null, result);
        } finally {
            restorePossibleStates(candidateRoot, possibleSnapshot);
            if (diagnostics) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST native aggregate candidate: path={}, key={}, requested={}, result={}, executeMs={}",
                        path, node.key, requestMultipliers, result,
                        (System.nanoTime() - startedAt) / 1_000_000.0);
            }
        }
    }

    private static long getExactCandidateOutputCount(
            AEKey key, IPatternDetails details) {
        if (key == null || details == null) {
            return 0;
        }
        long total = 0;
        try {
            for (GenericStack output : details.getOutputs()) {
                if (output != null && key.equals(output.what())
                        && output.amount() > 0) {
                    total = checkedAdd(
                            total, output.amount(),
                            "native_candidate_output_overflow");
                }
            }
            return total;
        } catch (Fallback | RuntimeException exception) {
            return 0;
        }
    }

    /**
     * Uses the compact first-candidate simulation model as an equivalence proof,
     * then commits one compiled aggregate. Terminal shortages remain simulated
     * missing inputs and nested ordered nodes keep candidate-zero priority.
     */
    private static boolean tryExecuteSparseSimulationFirstCandidate(
            Graph graph, int nodeIndex, CompiledCandidate ignoredFirstCandidate,
            CraftingSimulationState parent, long requestMultipliers,
            long rootRequestedAmount, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, String path,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot,
            boolean diagnostics)
            throws InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        boolean logHighCost = diagnostics || requestMultipliers >= 1_000;
        OmniSparseCapacitySolver.Plan plan = tryPlanSparseCandidateMix(
                graph, nodeIndex, parent, requestMultipliers,
                requestInput, pauseCheckpoint,
                path + "_simulation_sparse", logHighCost, true);
        if (plan == null || !plan.supported() || !plan.complete()) {
            return false;
        }

        long[] allocations = plan.candidateAllocations();
        int selectedCandidate = -1;
        for (int candidateIndex = 0;
                candidateIndex < allocations.length; candidateIndex++) {
            if (allocations[candidateIndex] <= 0) {
                continue;
            }
            if (selectedCandidate >= 0
                    || allocations[candidateIndex] != requestMultipliers
                    || candidateIndex >= node.compiledCandidates.size()) {
                return false;
            }
            selectedCandidate = candidateIndex;
        }
        if (selectedCandidate < 0) {
            return false;
        }
        CompiledCandidate candidate =
                node.compiledCandidates.get(selectedCandidate);

        var possibleSnapshot = snapshotPossibleStates(candidateRoot);
        CandidateAttempt attempt = executeCompiledCandidate(
                graph, nodeIndex, candidate, parent, requestMultipliers,
                rootRequestedAmount, true, pauseCheckpoint, requestInput,
                candidateRoot, possibleSnapshot, diagnostics,
                path + "_simulation_sparse");
        if (attempt.status != CandidateAttemptStatus.APPLIED) {
            restorePossibleStates(candidateRoot, possibleSnapshot);
            if (logHighCost) {
                AppliedEnhancements.LOGGER.warn(
                        "Omni MAX_FAST sparse simulation replay rejected: path={}, key={}, requested={}, reason={}",
                        path, node.key, requestMultipliers, attempt.reason);
            }
            return false;
        }

        attempt.inventory.applyDiff(parent);
        stagedMissing.addAll(attempt.missing);
        restorePossibleStates(candidateRoot, attempt.possibleStates);
        if (logHighCost) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST sparse simulation progressing candidate committed: path={}, key={}, requested={}, candidate={}, nodeVisits={}, noProgressSkipped={}",
                    path, node.key, requestMultipliers,
                    candidate.sourceIndex, plan.probes(),
                    plan.noProgressCandidatesSkipped());
        }
        return true;
    }

    /**
     * Mirrors AE2's multi-pattern simulation loop for a bounded number of
     * first-candidate pattern executions. Every compiled transaction requests
     * no more output than one pattern can provide, so the existing
     * single-pattern safety proof applies independently to each committed
     * step. This is slower than whole-batch aggregation but avoids rebuilding
     * a deep native crafting tree for small ordered requests.
     */
    private static boolean tryExecuteSimulationFirstCandidateLinearly(
            Graph graph, int nodeIndex, CompiledCandidate candidate,
            CraftingSimulationState parent, long requestMultipliers,
            long rootRequestedAmount, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, String path,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot,
            boolean diagnostics)
            throws CraftBranchFailure, InterruptedException, Fallback {
        Node node = graph.nodes.get(nodeIndex);
        if (!OmniSimulationSinglePattern.canReplayWithin(
                node.amount, requestMultipliers, candidate.outputPerPattern,
                MAX_SIMULATION_ORDERED_REPLAY_STEPS)) {
            node.orderedFallbackReason =
                    "simulation_linear_replay_limit";
            return false;
        }
        long chunk = OmniSimulationSinglePattern
                .maxRequestMultipliersPerPattern(
                        node.amount, candidate.outputPerPattern);
        if (chunk <= 0) {
            node.orderedFallbackReason =
                    "simulation_linear_replay_invalid_chunk";
            return false;
        }

        long remaining = requestMultipliers;
        long committed = 0;
        long steps = 0;
        var possibleSnapshot = snapshotPossibleStates(candidateRoot);
        while (remaining > 0) {
            checkpoint(pauseCheckpoint);
            long trialAmount = Math.min(remaining, chunk);
            CandidateAttempt attempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, parent, trialAmount,
                    rootRequestedAmount, true, pauseCheckpoint, requestInput,
                    candidateRoot, possibleSnapshot, diagnostics,
                    path + "_linear");
            if (attempt.status != CandidateAttemptStatus.APPLIED) {
                node.orderedFallbackReason =
                        "simulation_linear_replay:" + attempt.reason;
                node.orderedFallbackDetail =
                        "committed=" + committed + ",remaining=" + remaining;
                if (committed == 0) {
                    return false;
                }

                enforceOrderedChoiceNativeLimit(
                        graph, node, remaining, rootRequestedAmount,
                        path + "_linear_remainder");
                executeNativeBoundary(
                        node, parent, remaining,
                        path + "_linear_remainder", requestInput);
                return true;
            }

            attempt.inventory.applyDiff(parent);
            stagedMissing.addAll(attempt.missing);
            restorePossibleStates(candidateRoot, attempt.possibleStates);
            possibleSnapshot = attempt.possibleStates;
            remaining -= trialAmount;
            committed += trialAmount;
            steps++;
        }

        if (diagnostics || steps >= 1_000) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST simulation ordered first-candidate replay: path={}, key={}, requested={}, committed={}, steps={}, chunk={}",
                    path, node.key, requestMultipliers, committed, steps, chunk);
        }
        return true;
    }

    /**
     * Commits the largest proven candidate-zero prefix, then lets AE2 run the
     * untouched ordered chooser only for the remainder. The recursive
     * certificate makes successful-prefix probing monotonic and equivalent to
     * AE2's craft-by-craft candidate-zero sequence; later candidates remain
     * entirely native.
     */
    private static boolean tryExecuteCertifiedFirstCandidatePrefix(
            Graph graph, int nodeIndex, CompiledCandidate candidate,
            CraftingSimulationState parent, long requestMultipliers,
            long rootRequestedAmount, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, String path,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot,
            boolean diagnostics)
            throws CraftBranchFailure, InterruptedException, Fallback {
        Node node = graph.nodes.get(nodeIndex);
        OmniMaximumSuccessfulPrefix.Result<CandidateAttempt> prefix =
                OmniMaximumSuccessfulPrefix.find(
                        requestMultipliers, trialAmount -> {
                            CandidateAttempt trial;
                            try (var ignored =
                                    OmniFirstCandidatePrefixProbeScope.enter()) {
                                trial = executeCompiledCandidate(
                                        graph, nodeIndex, candidate, parent,
                                        trialAmount, rootRequestedAmount, false,
                                        pauseCheckpoint, requestInput, candidateRoot,
                                        possibleSnapshot, false, path);
                            }
                            return switch (trial.status) {
                                case APPLIED ->
                                        OmniMaximumSuccessfulPrefix.ProbeResult
                                                .applied(trial);
                                case SHORTAGE ->
                                        OmniMaximumSuccessfulPrefix.ProbeResult
                                                .shortage();
                                case FALLBACK ->
                                        OmniMaximumSuccessfulPrefix.ProbeResult
                                                .fallback();
                            };
                        });
        if (prefix.fallback() || prefix.allocation() <= 0
                || prefix.value() == null) {
            return false;
        }

        long remaining = requestMultipliers - prefix.allocation();
        if (remaining <= 0) {
            return false;
        }
        // Reject an unbounded native remainder before committing speculative
        // state. This preserves the controlled-rejection rollback contract.
        enforceOrderedChoiceNativeLimit(
                graph, node, remaining, rootRequestedAmount,
                path + "_prefix_remainder");

        CandidateAttempt best = prefix.value();
        best.inventory.applyDiff(parent);
        stagedMissing.addAll(best.missing);
        restorePossibleStates(candidateRoot, best.possibleStates);
        if (diagnostics || requestMultipliers >= 1_000) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST ordered first-candidate prefix: path={}, key={}, requested={}, allocated={}, nativeRemainder={}, probes={}",
                    path, node.key, requestMultipliers, prefix.allocation(),
                    remaining, prefix.probes());
        }

        executeNativeBoundary(
                node, parent, remaining, path + "_prefix_remainder",
                requestInput);
        return true;
    }

    private static boolean tryExecuteCompiledCandidateMix(Graph graph, int nodeIndex,
            CraftingSimulationState parent, long requestMultipliers,
            long rootRequestedAmount, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint,
            String path, GraphConsumableInput requestInput,
            CraftingTreeNode candidateRoot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot,
            boolean diagnostics, boolean requireSparseCapacityPlan)
            throws CraftBranchFailure, InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        var mixedInventory = new ChildCraftingSimulationState(parent);
        var mixedMissing = new KeyCounter();

        boolean logSparsePlan = diagnostics || requestMultipliers >= 1_000;
        OmniSparseCapacitySolver.Plan sparsePlan = tryPlanSparseCandidateMix(
                graph, nodeIndex, mixedInventory, requestMultipliers,
                requestInput, pauseCheckpoint, path, logSparsePlan, false);
        if (sparsePlan != null && sparsePlan.supported()) {
            if (!sparsePlan.complete()) {
                restorePossibleStates(candidateRoot, possibleSnapshot);
                throw new CraftBranchFailure(
                        node.key,
                        saturatedMultiply(node.amount, sparsePlan.remaining()));
            }

            long[] allocations = sparsePlan.candidateAllocations();
            for (int candidateIndex = 0;
                    candidateIndex < allocations.length; candidateIndex++) {
                long allocation = allocations[candidateIndex];
                if (allocation <= 0) {
                    continue;
                }
                checkpoint(pauseCheckpoint);
                CompiledCandidate candidate = node.compiledCandidates.get(candidateIndex);
                var candidateSnapshot = snapshotPossibleStates(candidateRoot);
                long replayStartedAt = diagnostics ? System.nanoTime() : 0;
                CandidateAttempt attempt = executeCompiledCandidate(
                        graph, nodeIndex, candidate, mixedInventory, allocation,
                        rootRequestedAmount, false, pauseCheckpoint, requestInput,
                        candidateRoot, candidateSnapshot, diagnostics, path);
                if (attempt.status != CandidateAttemptStatus.APPLIED) {
                    restorePossibleStates(candidateRoot, possibleSnapshot);
                    if (diagnostics) {
                        AppliedEnhancements.LOGGER.info(
                                "Omni MAX_FAST sparse capacity replay rejected: path={}, key={}, candidate={}, allocation={}, status={}",
                                path, node.key, candidate.sourceIndex,
                                allocation, attempt.status);
                    }
                    return false;
                }
                attempt.inventory.applyDiff(mixedInventory);
                mixedMissing.addAll(attempt.missing);
                restorePossibleStates(candidateRoot, attempt.possibleStates);
                logCandidateAllocation(
                        diagnostics, path, node, candidate,
                        requestMultipliers, allocation, 1, replayStartedAt);
            }

            mixedInventory.applyDiff(parent);
            stagedMissing.addAll(mixedMissing);
            return true;
        }

        if (requireSparseCapacityPlan) {
            // The direct-stock certificate proves only the compact capacity
            // model. If live templates invalidate or exceed that model, do not
            // drop into the older transactional binary allocator under a
            // different safety proof.
            restorePossibleStates(candidateRoot, possibleSnapshot);
            return false;
        }

        long remaining = requestMultipliers;

        for (CompiledCandidate candidate : node.compiledCandidates) {
            checkpoint(pauseCheckpoint);
            var candidateSnapshot = snapshotPossibleStates(candidateRoot);
            long candidateRequested = remaining;
            long allocationStartedAt = diagnostics ? System.nanoTime() : 0;
            int probes = 1;
            CandidateAttempt fullAttempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, mixedInventory, remaining,
                    rootRequestedAmount, false, pauseCheckpoint, requestInput,
                    candidateRoot, candidateSnapshot, diagnostics, path);
            if (fullAttempt.status == CandidateAttemptStatus.FALLBACK) {
                restorePossibleStates(candidateRoot, possibleSnapshot);
                return false;
            }
            if (fullAttempt.status == CandidateAttemptStatus.APPLIED) {
                fullAttempt.inventory.applyDiff(mixedInventory);
                mixedMissing.addAll(fullAttempt.missing);
                restorePossibleStates(candidateRoot, fullAttempt.possibleStates);
                remaining = 0;
                logCandidateAllocation(
                        diagnostics, path, node, candidate,
                        candidateRequested, candidateRequested,
                        probes, allocationStartedAt);
                break;
            }

            long low = 0;
            long high = remaining;
            CandidateAttempt bestAttempt = null;
            while (high - low > 1) {
                checkpoint(pauseCheckpoint);
                long trialAmount = OmniMaxFastExecutionPolicy.upperMidpoint(
                        low, high);
                probes++;
                CandidateAttempt trial = executeCompiledCandidate(
                        graph, nodeIndex, candidate, mixedInventory, trialAmount,
                        rootRequestedAmount, false, pauseCheckpoint, requestInput,
                        candidateRoot, candidateSnapshot, false, path);
                if (trial.status == CandidateAttemptStatus.FALLBACK) {
                    restorePossibleStates(candidateRoot, possibleSnapshot);
                    return false;
                }
                if (trial.status == CandidateAttemptStatus.APPLIED) {
                    low = trialAmount;
                    bestAttempt = trial;
                } else {
                    high = trialAmount;
                }
            }

            if (bestAttempt != null) {
                bestAttempt.inventory.applyDiff(mixedInventory);
                mixedMissing.addAll(bestAttempt.missing);
                restorePossibleStates(candidateRoot, bestAttempt.possibleStates);
                remaining -= low;
            }
            logCandidateAllocation(
                    diagnostics, path, node, candidate,
                    candidateRequested, low, probes, allocationStartedAt);
            if (remaining == 0) {
                break;
            }
        }

        if (remaining > 0) {
            restorePossibleStates(candidateRoot, possibleSnapshot);
            throw new CraftBranchFailure(
                    node.key, saturatedMultiply(node.amount, remaining));
        }
        mixedInventory.applyDiff(parent);
        stagedMissing.addAll(mixedMissing);
        return true;
    }

    /**
     * Builds a compact, exact-key model for the requested candidate subtree and
     * snapshots every involved inventory key once. Unsupported runtime
     * semantics return {@code null} and keep the established transactional
     * implementation as the compatibility path.
     */
    private static OmniSparseCapacitySolver.Plan tryPlanSparseCandidateMix(
            Graph graph, int nodeIndex, CraftingSimulationState inventory,
            long requestMultipliers, GraphConsumableInput requestInput,
            PauseCheckpoint pauseCheckpoint, String path, boolean diagnostics,
            boolean simulationFirstCandidate)
            throws InterruptedException {
        long startedAt = diagnostics ? System.nanoTime() : 0;
        var usedNodes = new boolean[graph.nodes.size()];
        var states = new byte[graph.nodes.size()];
        var liveTerminalNodes = new boolean[graph.nodes.size()];
        var reusableKeys = new LinkedHashMap<AEKey, Boolean>();
        var consumableTemplates =
                new IdentityHashMap<GraphConsumableInput, List<InputTemplate>>();
        var templateKeys = new LinkedHashMap<AEKey, Boolean>();
        var validatedReusableInputs =
                new IdentityHashMap<GraphReusableInput, Boolean>();

        if (!validateSparseRootTemplates(
                graph.nodes.get(nodeIndex), inventory,
                requestInput, pauseCheckpoint)) {
            return rejectSparseCapacityPlan(
                    graph, nodeIndex, requestMultipliers, path,
                    diagnostics, startedAt, "root_templates");
        }
        OmniCraftingTreeNodeBridge rootOccurrence;
        try {
            rootOccurrence = requestInput == null
                    ? (OmniCraftingTreeNodeBridge) graph.nodes.get(nodeIndex)
                            .occurrences.getFirst()
                    : requestInput.child;
        } catch (RuntimeException exception) {
            return rejectSparseCapacityPlan(
                    graph, nodeIndex, requestMultipliers, path,
                    diagnostics, startedAt, "root_occurrence");
        }
        if (!collectSparseCapacitySubgraph(
                        graph, nodeIndex, rootOccurrence, inventory,
                        usedNodes, states, liveTerminalNodes,
                        reusableKeys, consumableTemplates, templateKeys,
                        validatedReusableInputs, pauseCheckpoint,
                        simulationFirstCandidate)) {
            return rejectSparseCapacityPlan(
                    graph, nodeIndex, requestMultipliers, path,
                    diagnostics, startedAt, "unsupported_subgraph");
        }
        if (!validateSparseSubstituteGraphKeys(
                graph, usedNodes, consumableTemplates)) {
            return rejectSparseCapacityPlan(
                    graph, nodeIndex, requestMultipliers, path,
                    diagnostics, startedAt, "substitute_graph_conflict");
        }

        var keyIndexes = new LinkedHashMap<AEKey, Integer>();
        for (int index = 0; index < graph.nodes.size(); index++) {
            if (usedNodes[index]) {
                keyIndexes.computeIfAbsent(
                        graph.nodes.get(index).key, ignored -> keyIndexes.size());
            }
        }
        for (AEKey key : reusableKeys.keySet()) {
            keyIndexes.computeIfAbsent(key, ignored -> keyIndexes.size());
        }
        for (AEKey key : templateKeys.keySet()) {
            keyIndexes.computeIfAbsent(key, ignored -> keyIndexes.size());
        }
        if (keyIndexes.isEmpty()) {
            return rejectSparseCapacityPlan(
                    graph, nodeIndex, requestMultipliers, path,
                    diagnostics, startedAt, "empty_inventory_snapshot");
        }

        var modelNodes = new OmniSparseCapacitySolver.Node[graph.nodes.size()];
        int fallbackKeyIndex = 0;
        for (int index = 0; index < graph.nodes.size(); index++) {
            Node graphNode = graph.nodes.get(index);
            Integer keyIndex = keyIndexes.get(graphNode.key);
            if (!usedNodes[index] || keyIndex == null) {
                modelNodes[index] = OmniSparseCapacitySolver.Node.unsupported(
                        fallbackKeyIndex, Math.max(1, graphNode.amount));
                continue;
            }
            if (graphNode.emitter) {
                modelNodes[index] = OmniSparseCapacitySolver.Node.emitter(
                        keyIndex, graphNode.amount);
                continue;
            }
            if (graphNode.terminal || liveTerminalNodes[index]) {
                modelNodes[index] = OmniSparseCapacitySolver.Node.terminal(
                        keyIndex, graphNode.amount);
                continue;
            }

            List<CompiledCandidate> candidates = getSparseCapacityCandidates(
                    graph, graphNode, simulationFirstCandidate);
            if (candidates == null || candidates.isEmpty()) {
                return rejectSparseCapacityPlan(
                        graph, nodeIndex, requestMultipliers, path,
                        diagnostics, startedAt,
                        "unsupported_node:" + graphNode.key);
            }
            var modelCandidates =
                    new OmniSparseCapacitySolver.Candidate[candidates.size()];
            for (int candidateIndex = 0;
                    candidateIndex < candidates.size(); candidateIndex++) {
                modelCandidates[candidateIndex] = createSparseCapacityCandidate(
                        candidates.get(candidateIndex), keyIndexes,
                        consumableTemplates);
                if (modelCandidates[candidateIndex] == null) {
                    return rejectSparseCapacityPlan(
                            graph, nodeIndex, requestMultipliers, path,
                            diagnostics, startedAt,
                            "candidate_model:" + graphNode.key);
                }
            }
            modelNodes[index] = OmniSparseCapacitySolver.Node.craftable(
                    keyIndex, graphNode.amount, modelCandidates);
        }

        var snapshot = new long[keyIndexes.size()];
        for (var entry : keyIndexes.entrySet()) {
            checkpoint(pauseCheckpoint);
            snapshot[entry.getValue()] = inventory.extract(
                    entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
        }

        OmniSparseCapacitySolver.Plan plan;
        try {
            var model = new OmniSparseCapacitySolver.Model(
                    keyIndexes.size(), modelNodes);
            plan = simulationFirstCandidate
                    ? OmniSparseCapacitySolver.planSimulationFirstCandidate(
                            model, nodeIndex, requestMultipliers, snapshot)
                    : OmniSparseCapacitySolver.plan(
                            model, nodeIndex, requestMultipliers, snapshot);
        } catch (IllegalArgumentException exception) {
            return rejectSparseCapacityPlan(
                    graph, nodeIndex, requestMultipliers, path,
                    diagnostics, startedAt, "invalid_model");
        }

        if (diagnostics) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST sparse capacity plan: path={}, key={}, requested={}, mode={}, complete={}, remaining={}, candidates={}, keys={}, probes={}, equivalentSkipped={}, noProgressSkipped={}, planMs={}",
                    path, graph.nodes.get(nodeIndex).key,
                    requestMultipliers,
                    simulationFirstCandidate ? "simulation_first" : "real_mix",
                    plan.complete(), plan.remaining(),
                    plan.candidateAllocations().length, keyIndexes.size(),
                    plan.probes(), plan.equivalentCandidatesSkipped(),
                    plan.noProgressCandidatesSkipped(),
                    (System.nanoTime() - startedAt) / 1_000_000.0);
        }
        return plan;
    }

    private static OmniSparseCapacitySolver.Plan rejectSparseCapacityPlan(
            Graph graph, int nodeIndex, long requestMultipliers,
            String path, boolean diagnostics, long startedAt, String reason) {
        if (diagnostics) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST sparse capacity rejected: path={}, key={}, requested={}, reason={}, planMs={}",
                    path, graph.nodes.get(nodeIndex).key,
                    requestMultipliers, reason,
                    (System.nanoTime() - startedAt) / 1_000_000.0);
        }
        return null;
    }

    private static boolean validateSparseSubstituteGraphKeys(
            Graph graph, boolean[] usedNodes,
            IdentityHashMap<GraphConsumableInput, List<InputTemplate>> consumableTemplates) {
        for (var entry : consumableTemplates.entrySet()) {
            GraphConsumableInput consumable = entry.getKey();
            if (!consumable.substituteInput) {
                continue;
            }
            AEKey selectedKey = consumable.child.molecularmanipulator$getWhat();
            for (int nodeIndex = 0; nodeIndex < usedNodes.length; nodeIndex++) {
                if (!usedNodes[nodeIndex]) {
                    continue;
                }
                AEKey graphKey = graph.nodes.get(nodeIndex).key;
                if (selectedKey.equals(graphKey)
                        || containsTemplateKey(entry.getValue(), graphKey)) {
                    continue;
                }
                try {
                    if (consumable.input.isValid(
                            graphKey,
                            consumable.child.molecularmanipulator$getLevel())) {
                        return false;
                    }
                } catch (RuntimeException exception) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsTemplateKey(
            List<InputTemplate> templates, AEKey key) {
        for (InputTemplate template : templates) {
            if (key.equals(template.key())) {
                return true;
            }
        }
        return false;
    }

    private static boolean collectSparseCapacitySubgraph(
            Graph graph, int nodeIndex,
            OmniCraftingTreeNodeBridge occurrence,
            CraftingSimulationState inventory,
            boolean[] usedNodes, byte[] states,
            boolean[] liveTerminalNodes,
            Map<AEKey, Boolean> reusableKeys,
            IdentityHashMap<GraphConsumableInput, List<InputTemplate>> consumableTemplates,
            Map<AEKey, Boolean> templateKeys,
            IdentityHashMap<GraphReusableInput, Boolean> validatedReusableInputs,
            PauseCheckpoint pauseCheckpoint,
            boolean simulationFirstCandidate) throws InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        boolean liveTerminalOccurrence =
                isPrunedSimulationTerminalOccurrence(
                        node, occurrence, simulationFirstCandidate);
        byte state = states[nodeIndex];
        if (state == 2) {
            usedNodes[nodeIndex] = true;
            return node.terminal || node.emitter
                    || liveTerminalNodes[nodeIndex]
                            == liveTerminalOccurrence;
        }
        if (state == 1) {
            // Candidate graphs may contain a cyclic alternative beside a
            // productive recipe. Keep the component in the compact model; the
            // solver's demand-state guard will reject only the transition that
            // revisits it without reducing the outstanding deficit.
            usedNodes[nodeIndex] = true;
            return true;
        }
        if (state == 3) {
            return false;
        }
        states[nodeIndex] = 1;
        usedNodes[nodeIndex] = true;

        if (node.terminal || node.emitter) {
            states[nodeIndex] = 2;
            return true;
        }
        List<CompiledCandidate> candidates = getSparseCapacityCandidates(
                graph, node, simulationFirstCandidate);
        if (candidates == null || candidates.isEmpty()) {
            if (liveTerminalOccurrence) {
                liveTerminalNodes[nodeIndex] = true;
                states[nodeIndex] = 2;
                return true;
            }
            states[nodeIndex] = 3;
            return false;
        }

        for (CompiledCandidate candidate : candidates) {
            if (!hasDeterministicCandidateFlags(candidate)
                    || candidate.outputPerPattern <= 0
                    || simulationFirstCandidate
                            && !isKnownDeterministicPattern(candidate.details)) {
                states[nodeIndex] = 3;
                return false;
            }
            for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
                checkpoint(pauseCheckpoint);
                if (orderedInput.reusable()) {
                    GraphReusableInput reusable = orderedInput.reusableInput;
                    if (reusable.mode != BoundaryInputMode.INVARIANT_REUSABLE
                            || reusable.multiplier <= 0
                            || reusable.child.molecularmanipulator$getAmount() != 1
                            || validatedReusableInputs.put(
                                    reusable, Boolean.TRUE) == null
                                    && !validateSparseReusableTemplates(
                                            reusable, inventory,
                                            pauseCheckpoint)) {
                        states[nodeIndex] = 3;
                        return false;
                    }
                    reusableKeys.put(
                            reusable.child.molecularmanipulator$getWhat(), Boolean.TRUE);
                    continue;
                }

                GraphConsumableInput consumable = orderedInput.consumableInput;
                if (consumable == null || consumable.multiplier <= 0) {
                    states[nodeIndex] = 3;
                    return false;
                }
                if (!consumableTemplates.containsKey(consumable)) {
                    List<InputTemplate> templates = snapshotSparseConsumableTemplates(
                            graph.nodes.get(consumable.childIndex),
                            consumable, inventory, pauseCheckpoint);
                    if (templates == null) {
                        states[nodeIndex] = 3;
                        return false;
                    }
                    consumableTemplates.put(consumable, templates);
                    if (consumable.substituteInput) {
                        for (InputTemplate template : templates) {
                            templateKeys.put(template.key(), Boolean.TRUE);
                        }
                    }
                }
                if (!collectSparseCapacitySubgraph(
                                graph, consumable.childIndex,
                                consumable.child, inventory,
                                usedNodes, states, liveTerminalNodes,
                                reusableKeys,
                                consumableTemplates, templateKeys,
                                validatedReusableInputs, pauseCheckpoint,
                                simulationFirstCandidate)) {
                    states[nodeIndex] = 3;
                    return false;
                }
            }
        }
        states[nodeIndex] = 2;
        return true;
    }

    private static boolean isPrunedSimulationTerminalOccurrence(
            Node node, OmniCraftingTreeNodeBridge occurrence,
            boolean simulationFirstCandidate) {
        if (node == null || occurrence == null) {
            return false;
        }
        try {
            List<CraftingTreeProcess> processes =
                    occurrence.molecularmanipulator$getProcesses();
            return OmniMaxFastExecutionPolicy
                    .mayTreatMissingCompiledSimulationCandidateAsTerminal(
                            simulationFirstCandidate,
                            node.emitter,
                            !node.compiledCandidates.isEmpty(),
                            occurrence.molecularmanipulator$canEmit(),
                            processes != null,
                            processes != null && processes.isEmpty());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static List<CompiledCandidate> getSparseCapacityCandidates(
            Graph graph, Node node, boolean simulationFirstCandidate) {
        if (usesCompiledCandidateTrial(node)) {
            if (simulationFirstCandidate) {
                if (node.compiledCandidates.isEmpty()) {
                    return null;
                }
                boolean allSimulationShapesExact =
                        node.allCandidatesCompiled
                                && node.compiledCandidates.size()
                                        == node.candidatePatterns.size();
                if (allSimulationShapesExact) {
                    for (int candidateIndex = 0;
                            candidateIndex < node.compiledCandidates.size();
                            candidateIndex++) {
                        CompiledCandidate candidate =
                                node.compiledCandidates.get(candidateIndex);
                        if (candidate.sourceIndex != candidateIndex
                                || !isKnownDeterministicPattern(candidate.details)
                                || !hasOnlyExactPrimaryOutputs(node, candidate)
                                || !hasDeterministicCandidateFlags(candidate)) {
                            allSimulationShapesExact = false;
                            break;
                        }
                    }
                }
                return allSimulationShapesExact
                        ? node.compiledCandidates
                        : List.of(node.compiledCandidates.getFirst());
            }
            boolean allLocalShapesExact = true;
            for (int candidateIndex = 0;
                    candidateIndex < node.compiledCandidates.size();
                    candidateIndex++) {
                CompiledCandidate candidate =
                        node.compiledCandidates.get(candidateIndex);
                if (candidate.sourceIndex != candidateIndex
                        || !isKnownDeterministicPattern(candidate.details)
                        || !hasOnlyExactPrimaryOutputs(node, candidate)
                        || !hasDeterministicCandidateFlags(candidate)) {
                    allLocalShapesExact = false;
                    break;
                }
            }
            if (!OmniMaxFastExecutionPolicy
                    .mayModelSparseOrderedCandidateSet(
                            node.allCandidatesCompiled,
                            node.compiledCandidates.size(),
                            node.candidatePatterns.size(),
                            allLocalShapesExact)) {
                return null;
            }
            return node.compiledCandidates;
        }
        if (node.barrier
                || node.executionMode != ExecutionMode.PURE_FAST
                        && !isInvariantReusableBoundary(node)
                || node.compiledCandidates.isEmpty()) {
            return null;
        }
        return List.of(node.compiledCandidates.getFirst());
    }

    private static OmniSparseCapacitySolver.Candidate createSparseCapacityCandidate(
            CompiledCandidate candidate, Map<AEKey, Integer> keyIndexes,
            IdentityHashMap<GraphConsumableInput, List<InputTemplate>> consumableTemplates) {
        var inputs = new OmniSparseCapacitySolver.Input[
                candidate.orderedInputs.size()];
        for (int index = 0; index < candidate.orderedInputs.size(); index++) {
            OrderedGraphInput orderedInput = candidate.orderedInputs.get(index);
            if (orderedInput.reusable()) {
                GraphReusableInput reusable = orderedInput.reusableInput;
                Integer keyIndex = keyIndexes.get(
                        reusable.child.molecularmanipulator$getWhat());
                if (keyIndex == null) {
                    return null;
                }
                inputs[index] = OmniSparseCapacitySolver.Input.reusable(
                        keyIndex, reusable.multiplier);
            } else {
                GraphConsumableInput consumable = orderedInput.consumableInput;
                if (consumable.substituteInput) {
                    List<InputTemplate> templates = consumableTemplates.get(consumable);
                    if (templates == null) {
                        return null;
                    }
                    var modelTemplates = new OmniSparseCapacitySolver.Template[
                            templates.size()];
                    for (int templateIndex = 0;
                            templateIndex < templates.size(); templateIndex++) {
                        InputTemplate template = templates.get(templateIndex);
                        Integer keyIndex = keyIndexes.get(template.key());
                        if (keyIndex == null) {
                            return null;
                        }
                        modelTemplates[templateIndex] =
                                new OmniSparseCapacitySolver.Template(
                                        keyIndex, template.amount());
                    }
                    inputs[index] = OmniSparseCapacitySolver.Input.substitutable(
                            consumable.childIndex, consumable.multiplier,
                            modelTemplates);
                } else {
                    inputs[index] = OmniSparseCapacitySolver.Input.consumable(
                            consumable.childIndex, consumable.multiplier);
                }
            }
        }
        // Known deterministic pattern classes are fully described by the
        // compiled output and ordered sparse inputs. Duplicate encoded patterns
        // of the same class therefore have identical capacity and must not
        // trigger another binary search through the same descendant graph.
        return new OmniSparseCapacitySolver.Candidate(
                candidate.outputPerPattern,
                candidate.details == null ? null : candidate.details.getClass(),
                inputs);
    }

    private static boolean validateSparseRootTemplates(Node node,
            CraftingSimulationState inventory, GraphConsumableInput requestInput,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        if (requestInput != null) {
            return !requestInput.substituteInput
                    && validateSparseConsumableTemplates(
                            node, requestInput.child,
                            inventory, pauseCheckpoint);
        }
        return validateSparseConsumableTemplates(
                node,
                (OmniCraftingTreeNodeBridge) node.occurrences.getFirst(),
                inventory, pauseCheckpoint);
    }

    private static boolean validateSparseConsumableTemplates(Node node,
            OmniCraftingTreeNodeBridge child, CraftingSimulationState inventory,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        try {
            for (InputTemplate template
                    : child.molecularmanipulator$getValidItemTemplates(inventory)) {
                checkpoint(pauseCheckpoint);
                if (template == null || !node.key.equals(template.key())
                        || template.amount() != node.amount) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static List<InputTemplate> snapshotSparseConsumableTemplates(
            Node node, GraphConsumableInput consumable,
            CraftingSimulationState inventory,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        var templates = new ArrayList<InputTemplate>();
        try {
            for (InputTemplate template
                    : consumable.child.molecularmanipulator$getValidItemTemplates(
                            inventory)) {
                checkpoint(pauseCheckpoint);
                if (template == null || template.key() == null
                        || template.amount() <= 0) {
                    return null;
                }
                if (!consumable.substituteInput) {
                    if (!node.key.equals(template.key())
                            || template.amount() != node.amount) {
                        return null;
                    }
                    continue;
                }
                if (classifyRemainingKey(
                        consumable.input, template.key(),
                        consumable.child.molecularmanipulator$getLevel())
                        != BoundaryInputMode.CONSUMABLE) {
                    return null;
                }
                templates.add(template);
            }
            return consumable.substituteInput
                    ? List.copyOf(templates)
                    : List.of();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean validateSparseReusableTemplates(
            GraphReusableInput reusable, CraftingSimulationState inventory,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        GenericStack exact = getSingleExactInputChoice(reusable.input);
        AEKey expectedKey = reusable.child.molecularmanipulator$getWhat();
        if (exact == null || exact.amount() != 1
                || !expectedKey.equals(exact.what())) {
            return false;
        }
        try {
            for (InputTemplate template
                    : reusable.child.molecularmanipulator$getValidItemTemplates(inventory)) {
                checkpoint(pauseCheckpoint);
                if (template == null || !expectedKey.equals(template.key())
                        || template.amount() != 1) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static CandidateAttempt executeCompiledCandidate(Graph graph,
            int nodeIndex, CompiledCandidate candidate,
            CraftingSimulationState parent, long requestMultipliers,
            long rootRequestedAmount, boolean simulation,
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput,
            CraftingTreeNode candidateRoot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot,
            boolean diagnostics, String path)
            throws InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        long startedAt = diagnostics ? System.nanoTime() : 0;
        var candidateInventory = new ChildCraftingSimulationState(parent);
        var candidateMissing = new KeyCounter();
        CandidateAttemptStatus status = CandidateAttemptStatus.FALLBACK;
        String result = "unknown";
        try {
            executeTransactionalNode(
                    graph, nodeIndex, candidateInventory, requestMultipliers,
                    rootRequestedAmount, simulation, candidateMissing, pauseCheckpoint,
                    ProgressSink.NONE, nodeIndex, candidate, requestInput, null);
            status = CandidateAttemptStatus.APPLIED;
            result = "compiled_candidate_applied";
            return new CandidateAttempt(
                    status, candidateInventory, candidateMissing,
                    snapshotPossibleStates(candidateRoot), result);
        } catch (CraftBranchFailure failure) {
            status = CandidateAttemptStatus.SHORTAGE;
            result = "candidate_shortage";
            return new CandidateAttempt(status, null, null, null, result);
        } catch (CertifiedPrefixProbeFallback fallback) {
            result = fallback.reason;
            return new CandidateAttempt(status, null, null, null, result);
        } catch (Fallback fallback) {
            result = fallback.reason;
            return new CandidateAttempt(status, null, null, null, result);
        } catch (InterruptedException exception) {
            result = "interrupted";
            throw exception;
        } catch (RuntimeException | Error failure) {
            result = "candidate_internal_error";
            throw failure;
        } finally {
            restorePossibleStates(candidateRoot, possibleSnapshot);
            if (diagnostics) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST ordered choice candidate: path={}, key={}, amount={}, aggregatedRequest={}, simulation={}, candidate={}, compiledCandidates={}, totalCandidates={}, allCompiled={}, completed={}, result={}, executeMs={}",
                        path, node.key, node.amount, requestMultipliers,
                        simulation, candidate.sourceIndex,
                        node.compiledCandidates.size(), node.candidatePatterns.size(),
                        node.allCandidatesCompiled,
                        status == CandidateAttemptStatus.APPLIED, result,
                        (System.nanoTime() - startedAt) / 1_000_000.0);
            }
        }
    }

    private static void logCandidateAllocation(boolean diagnostics,
            String path, Node node, CompiledCandidate candidate,
            long requested, long allocated, int probes, long startedAt) {
        if (diagnostics) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST ordered choice allocation: path={}, key={}, amount={}, requested={}, allocated={}, candidate={}, probes={}, executeMs={}",
                    path, node.key, node.amount, requested, allocated,
                    candidate.sourceIndex, probes,
                    (System.nanoTime() - startedAt) / 1_000_000.0);
        }
    }

    private static boolean hasDeterministicCandidateSubgraph(
            Graph graph, Node node) {
        if (node.deterministicCandidateSubgraph != null) {
            return node.deterministicCandidateSubgraph;
        }
        if (!node.allCandidatesCompiled
                || node.compiledCandidates.size() != node.candidatePatterns.size()
                || !haveEqualCandidateOutputs(node.compiledCandidates)) {
            node.deterministicCandidateSubgraph = false;
            return false;
        }
        var states = new byte[graph.nodes.size()];
        for (CompiledCandidate candidate : node.compiledCandidates) {
            if (!isDeterministicCandidateSubgraph(graph, candidate, states)) {
                node.deterministicCandidateSubgraph = false;
                return false;
            }
        }
        node.deterministicCandidateSubgraph = true;
        return true;
    }

    private static boolean isLiveFirstCandidate(
            Node node, CraftingTreeNode candidateRoot) {
        if (candidateRoot == null || node.compiledCandidates.isEmpty()) {
            return false;
        }
        CompiledCandidate candidate = node.compiledCandidates.getFirst();
        if (candidate.sourceIndex != 0) {
            return false;
        }
        var rootBridge = (OmniCraftingTreeNodeBridge) candidateRoot;
        List<CraftingTreeProcess> processes = withoutNoProgressCandidates(
                node, rootBridge.molecularmanipulator$getProcesses());
        if (processes == null || processes.isEmpty()
                || processes.getFirst() != candidate.sourceProcess) {
            return false;
        }
        return isLiveCandidate(candidate, processes.getFirst());
    }

    private static boolean hasLiveCandidateSet(
            Node node, CraftingTreeNode candidateRoot) {
        if (candidateRoot == null || !node.allCandidatesCompiled
                || node.compiledCandidates.isEmpty()) {
            return false;
        }
        var rootBridge = (OmniCraftingTreeNodeBridge) candidateRoot;
        List<CraftingTreeProcess> processes = withoutNoProgressCandidates(
                node, rootBridge.molecularmanipulator$getProcesses());
        if (processes == null
                || processes.size() != node.candidatePatterns.size()) {
            return false;
        }
        for (int index = 0; index < node.compiledCandidates.size(); index++) {
            CompiledCandidate candidate = node.compiledCandidates.get(index);
            if (candidate.sourceIndex != index
                    || !isLiveCandidate(candidate, processes.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLiveCandidate(
            CompiledCandidate candidate, CraftingTreeProcess candidateProcess) {
        if (candidateProcess != candidate.sourceProcess) {
            return false;
        }
        var process = (OmniCraftingTreeProcessBridge) candidateProcess;
        return process.molecularmanipulator$isPossible()
                && process.molecularmanipulator$getDetails() == candidate.details
                && process.molecularmanipulator$hasContainerItems()
                        == candidate.hasContainerItems
                && process.molecularmanipulator$limitsQuantity()
                        == candidate.limitsQuantity;
    }

    /**
     * Proves that one aggregated first-candidate transaction contains no
     * uncompiled native leaf or stateful input. The single execution policy
     * permits a different, inventory-valid material allocation from AE2's
     * craft-by-craft chooser; the child transaction still provides atomic
     * commit/rollback. This is deliberately stricter than the capacity-probe
     * certificate, which admits native leaves.
     */
    private static boolean hasBatchValidFirstCandidateSubgraph(
            Graph graph, Node node) {
        if (node.batchValidFirstCandidateSubgraph != null) {
            return node.batchValidFirstCandidateSubgraph;
        }
        if (node.compiledCandidates.isEmpty()) {
            node.batchValidFirstCandidateSubgraph = false;
            return false;
        }
        var states = new byte[graph.nodes.size()];
        states[node.index] = 1;
        boolean safe = isBatchValidCandidateSubgraph(
                graph, node.compiledCandidates.getFirst(), states);
        states[node.index] = safe ? (byte) 2 : (byte) 3;
        node.batchValidFirstCandidateSubgraph = safe;
        return safe;
    }

    /**
     * Proves that AE2's simulated multi-pattern loop may be replaced by one
     * aggregated execution of candidate zero. Simulation is special here:
     * missing terminal inputs do not fail a candidate, so later candidates
     * must never be capacity-mixed into the result.
     */
    private static SimulationFirstCandidateProof certifySimulationFirstCandidate(
            Graph graph, int rootIndex, CraftingSimulationState inventory,
            GraphConsumableInput requestInput,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        Node root = graph.nodes.get(rootIndex);
        if (!validateSparseRootTemplates(
                root, inventory, requestInput, pauseCheckpoint)) {
            return SimulationFirstCandidateProof.reject("root_templates");
        }

        int nodeCount = graph.nodes.size();
        var models = new ArrayList<
                OmniSimulationFirstCandidateCertificate.Node<AEKey>>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            models.add(null);
        }
        var serviceIds = new SimulationServiceIds();
        var pending = new ArrayDeque<Integer>();
        var recoverableProcesses =
                new IdentityHashMap<CraftingTreeProcess, Boolean>();

        CraftingTreeNode requestedOccurrence;
        try {
            requestedOccurrence = requestInput == null
                    ? root.occurrences.getFirst()
                    : (CraftingTreeNode) requestInput.child;
        } catch (RuntimeException exception) {
            return SimulationFirstCandidateProof.reject("root_occurrence");
        }
        CandidateOccurrenceCheck requestedCheck =
                inspectSimulationFirstCandidateOccurrence(
                        root, requestedOccurrence,
                        requestedOccurrence == root.occurrences.getFirst(),
                        true, true);
        if (!requestedCheck.safe) {
            return SimulationFirstCandidateProof.reject(
                    "stale_requested_candidate0", requestedCheck.detail);
        }
        addRecoverableProcess(recoverableProcesses, requestedCheck);
        pending.addLast(rootIndex);

        while (!pending.isEmpty()) {
            checkpoint(pauseCheckpoint);
            int nodeIndex = pending.removeFirst();
            if (models.get(nodeIndex) != null) {
                continue;
            }
            Node node = graph.nodes.get(nodeIndex);
            CraftingTreeNode occurrence;
            try {
                occurrence = node.occurrences.getFirst();
            } catch (RuntimeException exception) {
                return SimulationFirstCandidateProof.reject(
                        "canonical_occurrence");
            }
            if (node.amount <= 0) {
                return SimulationFirstCandidateProof.reject("invalid_occurrence");
            }
            var occurrenceBridge = (OmniCraftingTreeNodeBridge) occurrence;
            if (node.emitter || occurrenceBridge.molecularmanipulator$canEmit()) {
                return SimulationFirstCandidateProof.reject("emitter");
            }
            if (node.terminal) {
                List<CraftingTreeProcess> liveProcesses =
                        occurrenceBridge.molecularmanipulator$getProcesses();
                if (liveProcesses == null || !liveProcesses.isEmpty()) {
                    return SimulationFirstCandidateProof.reject(
                            "stale_terminal");
                }
                models.set(nodeIndex,
                        new OmniSimulationFirstCandidateCertificate.Node<>(
                                node.key, node.amount, true, false,
                                true, true, 0,
                                serviceIds.terminal(node.key),
                                List.of()));
                continue;
            }
            if (node.barrier) {
                return SimulationFirstCandidateProof.reject("native_barrier");
            }

            boolean ordered = usesCompiledCandidateTrial(node);
            if (ordered) {
                if (node.executionMode != ExecutionMode.HYBRID_BARRIER
                        || !"ordered_pattern_choices".equals(
                                node.barrierReason)) {
                    return SimulationFirstCandidateProof.reject(
                            "ordered_boundary_shape");
                }
            } else if (node.executionMode != ExecutionMode.PURE_FAST
                    || !node.allCandidatesCompiled
                    || node.candidatePatterns.size() != 1
                    || node.compiledCandidates.size() != 1) {
                return SimulationFirstCandidateProof.reject("native_boundary");
            }
            if (node.compiledCandidates.isEmpty()) {
                return SimulationFirstCandidateProof.reject("missing_candidate0");
            }
            CompiledCandidate candidate = node.compiledCandidates.getFirst();
            CandidateOccurrenceCheck canonicalCheck =
                    inspectSimulationFirstCandidateOccurrence(
                            node, occurrence, true, true, true);
            if (!canonicalCheck.safe) {
                return SimulationFirstCandidateProof.reject(
                        "stale_candidate0", canonicalCheck.detail);
            }
            addRecoverableProcess(recoverableProcesses, canonicalCheck);
            if (!isKnownDeterministicPattern(candidate.details)) {
                return SimulationFirstCandidateProof.reject("unknown_pattern");
            }
            if (!hasOnlyExactPrimaryOutputs(node, candidate)
                    || candidate.outputPerPattern <= 0) {
                return SimulationFirstCandidateProof.reject(
                        "non_primary_output");
            }
            if (candidate.hasContainerItems || candidate.limitsQuantity
                    || candidate.quantityFeedbackBatch) {
                return SimulationFirstCandidateProof.reject(
                        "stateful_candidate");
            }

            var inputs = new ArrayList<
                    OmniSimulationFirstCandidateCertificate.Input<AEKey>>(
                            candidate.orderedInputs.size());
            for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
                checkpoint(pauseCheckpoint);
                if (orderedInput.reusable()) {
                    return SimulationFirstCandidateProof.reject(
                            "reusable_input");
                }
                GraphConsumableInput consumable = orderedInput.consumableInput;
                if (consumable == null
                        || consumable.multiplier <= 0
                        || consumable.childIndex < 0
                        || consumable.childIndex >= nodeCount) {
                    return SimulationFirstCandidateProof.reject(
                            "inexact_input");
                }
                Node child = graph.nodes.get(consumable.childIndex);
                GenericStack selected = consumable.substituteInput
                        ? getPrimaryInputChoice(consumable.input)
                        : getSingleExactInputChoice(consumable.input);
                if (selected == null || !child.key.equals(selected.what())
                        || child.amount != selected.amount()
                        || child.amount <= 0) {
                    return SimulationFirstCandidateProof.reject(
                            "input_shape");
                }
                try {
                    Math.multiplyExact(consumable.multiplier, child.amount);
                    if (!consumable.input.isValid(
                            child.key,
                            consumable.child.molecularmanipulator$getLevel())) {
                        return SimulationFirstCandidateProof.reject(
                                "input_validation");
                    }
                } catch (ArithmeticException exception) {
                    return SimulationFirstCandidateProof.reject(
                            "input_overflow");
                } catch (RuntimeException exception) {
                    return SimulationFirstCandidateProof.reject(
                            "input_validation_error");
                }

                List<OmniSimulationFirstCandidateCertificate.InventoryTemplate<AEKey>>
                        liveTemplates = List.of();
                if (consumable.substituteInput) {
                    List<InputTemplate> snapshot =
                            snapshotSparseConsumableTemplates(
                                    child, consumable, inventory,
                                    pauseCheckpoint);
                    if (snapshot == null) {
                        return SimulationFirstCandidateProof.reject(
                                "live_substitute_templates");
                    }
                    var templates = new ArrayList<
                            OmniSimulationFirstCandidateCertificate.InventoryTemplate<AEKey>>(
                                    snapshot.size());
                    for (InputTemplate template : snapshot) {
                        templates.add(new OmniSimulationFirstCandidateCertificate
                                .InventoryTemplate<>(
                                        template.key(), template.amount()));
                    }
                    liveTemplates = List.copyOf(templates);
                } else if (!validateSparseConsumableTemplates(
                        child, consumable.child, inventory,
                        pauseCheckpoint)) {
                    return SimulationFirstCandidateProof.reject(
                            "live_templates");
                }

                CraftingTreeNode childOccurrence;
                try {
                    childOccurrence = (CraftingTreeNode) consumable.child;
                } catch (ClassCastException exception) {
                    return SimulationFirstCandidateProof.reject(
                            "child_occurrence");
                }
                CandidateOccurrenceCheck childCheck =
                        inspectSimulationFirstCandidateOccurrence(
                                child, childOccurrence,
                                childOccurrence == child.occurrences.getFirst(),
                                true, true);
                if (!childCheck.safe) {
                    return SimulationFirstCandidateProof.reject(
                            "stale_child_candidate0", childCheck.detail);
                }
                addRecoverableProcess(recoverableProcesses, childCheck);
                pending.addLast(consumable.childIndex);

                IPatternDetails.IInput liveInput = consumable.input;
                var liveLevel =
                        consumable.child.molecularmanipulator$getLevel();
                inputs.add(new OmniSimulationFirstCandidateCertificate.Input<>(
                        consumable.childIndex, child.key, child.amount,
                        consumable.multiplier,
                        !consumable.substituteInput, true, liveTemplates,
                        producedKey -> liveInput.isValid(
                                producedKey, liveLevel)));
            }
            models.set(nodeIndex,
                    new OmniSimulationFirstCandidateCertificate.Node<>(
                            node.key, node.amount, false, false,
                            true, true, candidate.outputPerPattern,
                            serviceIds.candidate(
                                    candidate.details, node.key, node.amount),
                            inputs));
        }

        OmniSimulationFirstCandidateCertificate.Result<AEKey> result =
                OmniSimulationFirstCandidateCertificate.evaluate(
                        models, rootIndex);
        return result.safe()
                ? SimulationFirstCandidateProof.accept(
                        result.produced().size(),
                        List.copyOf(recoverableProcesses.keySet()))
                : SimulationFirstCandidateProof.reject(
                        result.reason(), result.detail());
    }

    private record SimulationServiceRequest(AEKey key, long amount) {
    }

    /**
     * The compiled graph executes the node's canonical occurrence. A request
     * edge may point at another occurrence that the compiler proved
     * structurally equivalent, so object identity is required only for the
     * canonical source process. Every actual edge occurrence must still expose
     * the same live candidate-zero details and possible/state flags.
     */
    private static boolean isLiveSimulationFirstCandidateOccurrence(
            Node node, CraftingTreeNode occurrence) {
        return inspectSimulationFirstCandidateOccurrence(
                node, occurrence,
                occurrence != null && !node.occurrences.isEmpty()
                        && occurrence == node.occurrences.getFirst(),
                false, true).safe;
    }

    private static CandidateOccurrenceCheck
            inspectSimulationFirstCandidateOccurrence(
                    Node node, CraftingTreeNode occurrence,
                    boolean requireCanonicalSource,
                    boolean allowStateRecovery, boolean simulation) {
        if (occurrence == null) {
            return CandidateOccurrenceCheck.reject(
                    candidateOccurrenceDetail(node, "missing_occurrence", null));
        }
        try {
            var bridge = (OmniCraftingTreeNodeBridge) occurrence;
            if (node.emitter != bridge.molecularmanipulator$canEmit()) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "emitter_mismatch", null));
            }
            List<CraftingTreeProcess> processes =
                    bridge.molecularmanipulator$getProcesses();
            processes = withoutNoProgressCandidates(node, processes);
            if (node.terminal) {
                return !node.emitter && processes != null && processes.isEmpty()
                        ? CandidateOccurrenceCheck.accept()
                        : CandidateOccurrenceCheck.reject(
                                candidateOccurrenceDetail(
                                        node, "terminal_process_mismatch", null));
            }
            if (node.emitter || node.compiledCandidates.isEmpty()) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "missing_compiled_candidate", null));
            }
            if (processes == null || processes.isEmpty()) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "missing_live_candidate", null));
            }
            CompiledCandidate candidate = node.compiledCandidates.getFirst();
            if (candidate.sourceIndex != 0) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "compiled_source_index="
                                        + candidate.sourceIndex, null));
            }
            CraftingTreeProcess liveCandidate = processes.getFirst();
            var process = (OmniCraftingTreeProcessBridge) liveCandidate;
            if (requireCanonicalSource
                    && liveCandidate != candidate.sourceProcess) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "source_process_identity", liveCandidate));
            }
            if (process.molecularmanipulator$getDetails()
                    != candidate.details) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "details_identity", liveCandidate));
            }
            if (process.molecularmanipulator$hasContainerItems()
                    != candidate.hasContainerItems) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "container_flag", liveCandidate));
            }
            if (process.molecularmanipulator$limitsQuantity()
                    != candidate.limitsQuantity) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "quantity_flag", liveCandidate));
            }
            if (process.molecularmanipulator$isPossible()) {
                return CandidateOccurrenceCheck.accept();
            }

            boolean statelessCandidate = !candidate.hasContainerItems
                    && !candidate.limitsQuantity
                    && !candidate.quantityFeedbackBatch;
            if (allowStateRecovery && OmniMaxFastExecutionPolicy
                    .mayRecoverSimulationCandidateState(
                            simulation,
                            isKnownDeterministicPattern(candidate.details),
                            hasOnlyExactPrimaryOutputs(node, candidate),
                            statelessCandidate, true)) {
                return CandidateOccurrenceCheck.recover(liveCandidate);
            }
            return CandidateOccurrenceCheck.reject(
                    candidateOccurrenceDetail(
                            node, "possible_false", liveCandidate));
        } catch (RuntimeException exception) {
            return CandidateOccurrenceCheck.reject(
                    candidateOccurrenceDetail(
                            node, "validation_error:"
                                    + exception.getClass().getSimpleName(), null));
        }
    }

    private static String candidateOccurrenceDetail(
            Node node, String mismatch, CraftingTreeProcess liveCandidate) {
        return "key=" + (node == null ? "unknown" : node.key)
                + ",node=" + (node == null ? -1 : node.index)
                + ",mismatch=" + mismatch
                + ",liveProcess=" + (liveCandidate == null
                        ? "missing"
                        : Integer.toHexString(
                                System.identityHashCode(liveCandidate)));
    }

    private static List<CraftingTreeProcess> withoutNoProgressCandidates(
            Node node, List<CraftingTreeProcess> processes) {
        if (processes == null || processes.isEmpty() || node == null
                || node.noProgressPatterns.isEmpty()) {
            return processes;
        }
        var result = new ArrayList<CraftingTreeProcess>(processes.size());
        for (CraftingTreeProcess process : processes) {
            try {
                IPatternDetails details =
                        ((OmniCraftingTreeProcessBridge) process)
                                .molecularmanipulator$getDetails();
                if (node.noProgressPatterns.containsKey(details)) {
                    continue;
                }
            } catch (RuntimeException exception) {
                // Keep unknown live processes visible so the ordinary identity
                // validation rejects them instead of silently pruning them.
            }
            result.add(process);
        }
        return List.copyOf(result);
    }

    private static void addRecoverableProcess(
            IdentityHashMap<CraftingTreeProcess, Boolean> processes,
            CandidateOccurrenceCheck check) {
        if (check.recoverableProcess != null) {
            processes.put(check.recoverableProcess, Boolean.TRUE);
        }
    }

    private static boolean isLiveSimulationRequestedOccurrence(
            Node node, GraphConsumableInput requestInput,
            CraftingTreeNode canonicalOccurrence) {
        try {
            CraftingTreeNode requestedOccurrence = requestInput == null
                    ? canonicalOccurrence
                    : (CraftingTreeNode) requestInput.child;
            return isLiveSimulationFirstCandidateOccurrence(
                    node, requestedOccurrence);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private record CandidateOccurrenceCheck(boolean safe,
            CraftingTreeProcess recoverableProcess, String detail) {
        private static CandidateOccurrenceCheck accept() {
            return new CandidateOccurrenceCheck(true, null, null);
        }

        private static CandidateOccurrenceCheck recover(
                CraftingTreeProcess process) {
            return new CandidateOccurrenceCheck(true, process, null);
        }

        private static CandidateOccurrenceCheck reject(String detail) {
            return new CandidateOccurrenceCheck(false, null, detail);
        }
    }

    private record SimulationFirstCandidateProof(
            boolean safe, String reason, String detail, int producedKeys,
            List<CraftingTreeProcess> recoverableProcesses) {
        private static SimulationFirstCandidateProof accept(int producedKeys) {
            return new SimulationFirstCandidateProof(
                    true, null, null, producedKeys, List.of());
        }

        private static SimulationFirstCandidateProof accept(
                int producedKeys,
                List<CraftingTreeProcess> recoverableProcesses) {
            return new SimulationFirstCandidateProof(
                    true, null, null, producedKeys,
                    recoverableProcesses == null
                            ? List.of() : List.copyOf(recoverableProcesses));
        }

        private static SimulationFirstCandidateProof reject(String reason) {
            return reject(reason, null);
        }

        private static SimulationFirstCandidateProof reject(
                String reason, String detail) {
            return new SimulationFirstCandidateProof(
                    false, reason == null ? "unknown" : reason, detail, 0,
                    List.of());
        }
    }

    /**
     * Simulation-specific certificate for one exact, direct-stock first
     * candidate. Unlike the real AGGRESSIVE certificate, this proof admits no
     * descendants whose craft ordering could change the simulated missing
     * list.
     */
    private static boolean hasDirectStockFirstCandidate(
            Graph graph, Node node) {
        if (node.directStockFirstCandidate != null) {
            return node.directStockFirstCandidate;
        }
        boolean safe = OmniMaxFastExecutionPolicy
                .supportsDirectStockOutputMix(node.amount)
                && !node.compiledCandidates.isEmpty()
                && isDirectStockCandidate(
                        graph, node, node.compiledCandidates.getFirst(), 0);
        node.directStockFirstCandidate = safe;
        return safe;
    }

    /**
     * Strict certificate for the common large ordered choice whose candidates
     * consume only exact items already present in storage. Different candidate
     * output counts are safe for the sparse/binary capacity mixer because no
     * descendant recipe, emitter, remainder or substitution can feed another
     * candidate while allocations are probed.
     */
    private static boolean hasDirectStockCandidateSet(Graph graph, Node node) {
        if (node.directStockCandidateSet != null) {
            return node.directStockCandidateSet;
        }
        if (!OmniMaxFastExecutionPolicy
                .supportsDirectStockOutputMix(node.amount)
                || !node.allCandidatesCompiled
                || node.compiledCandidates.isEmpty()
                || node.compiledCandidates.size() != node.candidatePatterns.size()) {
            node.directStockCandidateSet = false;
            return false;
        }
        for (int candidateIndex = 0;
                candidateIndex < node.compiledCandidates.size(); candidateIndex++) {
            CompiledCandidate candidate = node.compiledCandidates.get(candidateIndex);
            if (!isDirectStockCandidate(
                    graph, node, candidate, candidateIndex)) {
                node.directStockCandidateSet = false;
                return false;
            }
        }
        node.directStockCandidateSet = true;
        return true;
    }

    private static boolean isDirectStockCandidate(
            Graph graph, Node node, CompiledCandidate candidate,
            int expectedSourceIndex) {
        try {
            return isDirectStockCandidateUnchecked(
                    graph, node, candidate, expectedSourceIndex);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isDirectStockCandidateUnchecked(
            Graph graph, Node node, CompiledCandidate candidate,
            int expectedSourceIndex) {
        if (candidate.sourceIndex != expectedSourceIndex
                || candidate.outputPerPattern <= 0
                || candidate.hasContainerItems
                || candidate.limitsQuantity
                || candidate.quantityFeedbackBatch
                || candidate.orderedInputs.isEmpty()
                || !isKnownDeterministicPattern(candidate.details)
                || !hasOnlyExactPrimaryOutputs(node, candidate)) {
            return false;
        }
        for (OrderedGraphInput input : candidate.orderedInputs) {
            if (input.reusable()) {
                return false;
            }
            GraphConsumableInput consumable = input.consumableInput;
            if (consumable == null || consumable.substituteInput
                    || consumable.multiplier <= 0
                    || consumable.childIndex < 0
                    || consumable.childIndex >= graph.nodes.size()) {
                return false;
            }
            Node child = graph.nodes.get(consumable.childIndex);
            GenericStack exactInput = getSingleExactInputChoice(
                    consumable.input);
            List<CraftingTreeProcess> childProcesses =
                    consumable.child.molecularmanipulator$getProcesses();
            if (exactInput == null
                    || !child.key.equals(exactInput.what())
                    || exactInput.amount() != child.amount
                    || child.amount <= 0
                    || !child.terminal || child.emitter || child.barrier
                    || consumable.child.molecularmanipulator$canEmit()
                    || childProcesses == null || !childProcesses.isEmpty()
                    || !child.candidatePatterns.isEmpty()
                    || !child.compiledCandidates.isEmpty()
                    || !child.orderedInputs.isEmpty()
                    || !child.edges.isEmpty()
                    || !child.dependencyEdges.isEmpty()
                    || !child.reusableInputs.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean validateLiveDirectStockCandidate(
            Graph graph, Node node, CompiledCandidate candidate,
            CraftingSimulationState inventory,
            GraphConsumableInput requestInput,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        if (!validateSparseRootTemplates(
                node, inventory, requestInput, pauseCheckpoint)) {
            return false;
        }
        for (OrderedGraphInput input : candidate.orderedInputs) {
            if (input.reusable()) {
                return false;
            }
            GraphConsumableInput consumable = input.consumableInput;
            if (consumable == null
                    || !validateSparseConsumableTemplates(
                            graph.nodes.get(consumable.childIndex),
                            consumable.child, inventory, pauseCheckpoint)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKnownDeterministicPattern(
            IPatternDetails details) {
        try {
            return getPatternBarrierReason(details) == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasExactStatelessCandidateInputs(
            CompiledCandidate candidate) {
        if (candidate.hasContainerItems || candidate.limitsQuantity
                || candidate.quantityFeedbackBatch) {
            return false;
        }
        for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
            if (orderedInput.reusable()) {
                return false;
            }
            GraphConsumableInput consumable = orderedInput.consumableInput;
            if (consumable == null || consumable.substituteInput
                    || consumable.multiplier <= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOnlyExactPrimaryOutputs(
            Node node, CompiledCandidate candidate) {
        if (candidate.details == null) {
            return false;
        }
        try {
            int outputEntries = 0;
            long totalOutput = 0;
            for (GenericStack output : candidate.details.getOutputs()) {
                if (output == null || output.what() == null || output.amount() <= 0
                        || !node.key.equals(output.what())) {
                    return false;
                }
                if (totalOutput > Long.MAX_VALUE - output.amount()) {
                    return false;
                }
                totalOutput += output.amount();
                outputEntries++;
            }
            return outputEntries > 0
                    && totalOutput == candidate.outputPerPattern;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isBatchValidCandidateSubgraph(
            Graph graph, CompiledCandidate candidate, byte[] states) {
        if (candidate.outputPerPattern <= 0
                || candidate.hasContainerItems
                || candidate.limitsQuantity
                || candidate.quantityFeedbackBatch) {
            return false;
        }
        for (OrderedGraphInput input : candidate.orderedInputs) {
            // Even invariant remainders alter the slot-by-slot inventory seen
            // by later inputs, so they are outside this aggregate proof.
            if (input.reusable()) {
                return false;
            }
            GraphConsumableInput consumable = input.consumableInput;
            if (consumable == null || consumable.substituteInput
                    || consumable.multiplier <= 0
                    || !isBatchValidNodeSubgraph(
                            graph, consumable.childIndex, states)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBatchValidNodeSubgraph(
            Graph graph, int nodeIndex, byte[] states) {
        if (nodeIndex < 0 || nodeIndex >= graph.nodes.size()) {
            return false;
        }
        byte state = states[nodeIndex];
        if (state == 2) {
            return true;
        }
        if (state == 1 || state == 3) {
            return false;
        }
        states[nodeIndex] = 1;

        Node node = graph.nodes.get(nodeIndex);
        boolean safe;
        if (node.terminal || node.emitter) {
            safe = true;
        } else if (usesCompiledCandidateTrial(node)) {
            // A nested ordered choice is admissible only when its complete
            // ordered set was compiled and its first choice is itself batch
            // valid. Runtime execution will use the same first-choice-first
            // policy and remains enclosed by the outer child transaction.
            safe = node.allCandidatesCompiled
                    && !node.barrier
                    && node.compiledCandidates.size()
                            == node.candidatePatterns.size()
                    && !node.compiledCandidates.isEmpty()
                    && isBatchValidCandidateSubgraph(
                            graph, node.compiledCandidates.getFirst(), states);
        } else if (node.barrier
                || node.executionMode != ExecutionMode.PURE_FAST
                || node.hasContainerItems
                || node.limitsQuantity
                || !node.allCandidatesCompiled
                || node.candidatePatterns.size() != 1
                || node.compiledCandidates.size() != 1) {
            // No native/ordered boundary is admitted. Binary-search-compatible
            // leaves are not necessarily exchangeable across outer crafts.
            safe = false;
        } else {
            safe = isBatchValidCandidateSubgraph(
                    graph, node.compiledCandidates.getFirst(), states);
        }
        states[nodeIndex] = safe ? (byte) 2 : (byte) 3;
        return safe;
    }

    private static boolean isDeterministicCandidateSubgraph(
            Graph graph, CompiledCandidate candidate, byte[] states) {
        if (!hasDeterministicCandidateFlags(candidate)) {
            return false;
        }
        for (OrderedGraphInput input : candidate.orderedInputs) {
            if (input.reusable()) {
                GraphReusableInput reusable = input.reusableInput;
                if (reusable.mode != BoundaryInputMode.INVARIANT_REUSABLE
                        || reusable.multiplier <= 0
                        || reusable.child.molecularmanipulator$getAmount() != 1) {
                    return false;
                }
            } else if (!isDeterministicNodeSubgraph(
                    graph, input.consumableInput.childIndex, states)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDeterministicNodeSubgraph(
            Graph graph, int nodeIndex, byte[] states) {
        byte state = states[nodeIndex];
        if (state == 2) {
            return true;
        }
        if (state == 1 || state == 3) {
            return false;
        }
        states[nodeIndex] = 1;

        Node node = graph.nodes.get(nodeIndex);
        boolean deterministic;
        if (node.terminal || node.emitter) {
            deterministic = true;
        } else if (usesCompiledCandidateTrial(node)) {
            deterministic = node.allCandidatesCompiled
                    && node.compiledCandidates.size() == node.candidatePatterns.size()
                    && haveEqualCandidateOutputs(node.compiledCandidates);
            if (deterministic) {
                for (CompiledCandidate candidate : node.compiledCandidates) {
                    if (!isDeterministicCandidateSubgraph(
                            graph, candidate, states)) {
                        deterministic = false;
                        break;
                    }
                }
            }
        } else if (node.barrier) {
            // These native leaves remain monotonic for consumable inputs. Each
            // binary-search probe already runs in a child transaction, so AE2
            // still owns their secondary-output and quantity-limit semantics.
            deterministic = OmniMaxFastExecutionPolicy
                    .isBinarySearchCompatibleNativeLeaf(node.barrierReason);
        } else if (node.compiledCandidates.isEmpty()
                || !hasDeterministicCandidateFlags(
                        node.compiledCandidates.getFirst())
                || node.executionMode != ExecutionMode.PURE_FAST
                        && !isInvariantReusableBoundary(node)) {
            deterministic = false;
        } else {
            deterministic = true;
            for (OrderedGraphInput input : node.orderedInputs) {
                if (input.reusable()) {
                    GraphReusableInput reusable = input.reusableInput;
                    if (reusable.mode != BoundaryInputMode.INVARIANT_REUSABLE
                            || reusable.multiplier <= 0
                            || reusable.child.molecularmanipulator$getAmount() != 1) {
                        deterministic = false;
                        break;
                    }
                } else if (!isDeterministicNodeSubgraph(
                        graph, input.consumableInput.childIndex, states)) {
                    deterministic = false;
                    break;
                }
            }
        }

        states[nodeIndex] = deterministic ? (byte) 2 : (byte) 3;
        return deterministic;
    }

    private static boolean haveEqualCandidateOutputs(
            List<CompiledCandidate> candidates) {
        if (candidates.isEmpty()) {
            return false;
        }
        long outputPerPattern = candidates.getFirst().outputPerPattern;
        if (outputPerPattern <= 0) {
            return false;
        }
        for (CompiledCandidate candidate : candidates) {
            if (candidate.outputPerPattern != outputPerPattern) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDeterministicCandidateFlags(
            CompiledCandidate candidate) {
        if (candidate.quantityFeedbackBatch) {
            // compileCandidate sets this flag only after proving a single,
            // exact, remainder-free and net-positive output-as-input shape.
            // Its feedback edge targets a distinct recursion-filtered terminal
            // occurrence, so sparse/determinism traversal has no graph cycle.
            return candidate.limitsQuantity && !candidate.hasContainerItems;
        }
        if (!candidate.hasContainerItems && !candidate.limitsQuantity) {
            return true;
        }
        if (!candidate.hasContainerItems || hasPatternOutputAsInput(candidate.details)) {
            return false;
        }

        boolean hasInvariantReusable = false;
        for (OrderedGraphInput input : candidate.orderedInputs) {
            if (!input.reusable()) {
                continue;
            }
            GraphReusableInput reusable = input.reusableInput;
            if (reusable.mode != BoundaryInputMode.INVARIANT_REUSABLE
                    || reusable.multiplier <= 0
                    || reusable.child.molecularmanipulator$getAmount() != 1) {
                return false;
            }
            hasInvariantReusable = true;
        }
        return hasInvariantReusable;
    }

    private static boolean isInvariantReusableBoundary(Node node) {
        return "container_items".equals(node.barrierReason)
                && !node.barrier
                && !node.compiledCandidates.isEmpty()
                && hasDeterministicCandidateFlags(
                        node.compiledCandidates.getFirst());
    }

    private static boolean hasPatternOutputAsInput(IPatternDetails details) {
        if (details == null) {
            return true;
        }
        try {
            for (IPatternDetails.IInput input : details.getInputs()) {
                GenericStack primary = getPrimaryInputChoice(input);
                if (primary == null) {
                    return true;
                }
                for (GenericStack output : details.getOutputs()) {
                    if (output != null && output.what() != null
                            && output.what().matches(primary)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static IdentityHashMap<CraftingTreeProcess, Boolean> snapshotPossibleStates(
            CraftingTreeNode root) {
        var result = new IdentityHashMap<CraftingTreeProcess, Boolean>();
        visitBuiltProcesses(root, process -> result.put(
                process,
                ((OmniCraftingTreeProcessBridge) process)
                        .molecularmanipulator$isPossible()));
        return result;
    }

    private static int enableSimulationCandidateRecovery(
            SimulationFirstCandidateProof proof,
            IdentityHashMap<CraftingTreeProcess, Boolean> snapshot) {
        if (proof == null || !proof.safe
                || proof.recoverableProcesses.isEmpty()) {
            return 0;
        }
        int recovered = 0;
        for (CraftingTreeProcess process : proof.recoverableProcesses) {
            if (process == null) {
                continue;
            }
            var bridge = (OmniCraftingTreeProcessBridge) process;
            boolean previous = bridge.molecularmanipulator$isPossible();
            snapshot.putIfAbsent(process, previous);
            if (!previous) {
                bridge.molecularmanipulator$setPossible(true);
                recovered++;
            }
        }
        return recovered;
    }

    /** Keep a recovered flag transactional even when the candidate succeeds. */
    private static void preserveRecoveredCandidateStates(
            CandidateAttempt attempt, SimulationFirstCandidateProof proof,
            IdentityHashMap<CraftingTreeProcess, Boolean> originalSnapshot) {
        if (attempt == null || attempt.possibleStates == null
                || proof == null || proof.recoverableProcesses.isEmpty()) {
            return;
        }
        for (CraftingTreeProcess process : proof.recoverableProcesses) {
            Boolean original = originalSnapshot.get(process);
            if (process != null && original != null) {
                attempt.possibleStates.put(process, original);
            }
        }
    }

    private static void restorePossibleStates(CraftingTreeNode root,
            IdentityHashMap<CraftingTreeProcess, Boolean> snapshot) {
        visitBuiltProcesses(root, process -> {
            Boolean previous = snapshot.get(process);
            ((OmniCraftingTreeProcessBridge) process)
                    .molecularmanipulator$setPossible(previous == null || previous);
        });
        // Recovery proofs may reference a structurally equivalent occurrence
        // outside the canonical root walk. Restore every captured identity as
        // well so speculative state can never escape its transaction.
        for (var entry : snapshot.entrySet()) {
            ((OmniCraftingTreeProcessBridge) entry.getKey())
                    .molecularmanipulator$setPossible(entry.getValue());
        }
    }

    private static void visitBuiltProcesses(CraftingTreeNode root,
            java.util.function.Consumer<CraftingTreeProcess> visitor) {
        var pending = new ArrayDeque<CraftingTreeNode>();
        var visited = new IdentityHashMap<CraftingTreeNode, Boolean>();
        pending.addLast(root);
        while (!pending.isEmpty()) {
            CraftingTreeNode treeNode = pending.removeFirst();
            if (visited.put(treeNode, Boolean.TRUE) != null) {
                continue;
            }
            var nodeBridge = (OmniCraftingTreeNodeBridge) treeNode;
            var processes = nodeBridge.molecularmanipulator$getProcesses();
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

    private static void executeNativeBoundary(Node node,
            CraftingSimulationState inventory, long requestedAmount, String path)
            throws CraftBranchFailure, InterruptedException, Fallback {
        executeNativeBoundary(node, inventory, requestedAmount, path, null);
    }

    private static void executeNativeBoundary(Node node,
            CraftingSimulationState inventory, long requestedAmount, String path,
            GraphConsumableInput requestInput)
            throws CraftBranchFailure, InterruptedException, Fallback {
        if (!OmniMaxFastExecutionPolicy.mayExecuteNativeBoundary(
                node.amount, requestedAmount, MAX_LINEAR_NATIVE_BOUNDARY_ITEMS)) {
            AppliedEnhancements.LOGGER.warn(
                    "Omni MAX_FAST delegated oversized native boundary: path={}, key={}, amount={}, aggregatedRequest={}, reason={}, linearLimit={}",
                    path, node.key, node.amount, requestedAmount,
                    node.barrierReason, MAX_LINEAR_NATIVE_BOUNDARY_ITEMS);
            throw new Fallback("native_boundary_work_limit:" + node.barrierReason);
        }
        boolean diagnostics = Config.MAX_FAST_DIAGNOSTICS.get();
        long startedAt = System.nanoTime();
        boolean completed = false;
        try {
            var bridge = requestInput == null
                    ? (OmniCraftingTreeNodeBridge) node.occurrences.getFirst()
                    : requestInput.child;
            bridge.molecularmanipulator$request(inventory, requestedAmount, null);
            completed = true;
        } finally {
            long elapsedNanos = System.nanoTime() - startedAt;
            if (OmniMaxFastExecutionPolicy.shouldLogNativeBoundary(
                    diagnostics, completed, elapsedNanos)) {
                if (diagnostics) {
                    AppliedEnhancements.LOGGER.info(
                            "Omni MAX_FAST local native boundary: path={}, key={}, amount={}, aggregatedRequest={}, reason={}, compiledFallback={}, compiledFallbackDetail={}, completed={}, executeMs={}",
                            path, node.key, node.amount, requestedAmount, node.barrierReason,
                            node.orderedFallbackReason,
                            node.orderedFallbackDetail,
                            completed, elapsedNanos / 1_000_000.0);
                } else {
                    AppliedEnhancements.LOGGER.warn(
                            "Omni MAX_FAST local native boundary: path={}, key={}, amount={}, aggregatedRequest={}, reason={}, compiledFallback={}, compiledFallbackDetail={}, completed={}, executeMs={}",
                            path, node.key, node.amount, requestedAmount, node.barrierReason,
                            node.orderedFallbackReason,
                            node.orderedFallbackDetail,
                            completed, elapsedNanos / 1_000_000.0);
                }
            }
        }
    }

    private static boolean tryExecuteReusableContainerBoundary(Node node,
            CraftingSimulationState parent, long requestedAmount,
            PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        try {
            return tryExecuteReusableContainerBoundaryUnchecked(
                    node, parent, requestedAmount, pauseCheckpoint);
        } catch (NoSuchElementException exception) {
            return rejectReusableBoundary(node, null, "provider_missing", exception);
        }
    }

    private static boolean isReusableBoundaryReason(String reason) {
        return "container_items".equals(reason)
                || "recursive_durability_input".equals(reason)
                || "fuzzy_crafted_input".equals(reason);
    }

    private static boolean tryExecuteReusableContainerBoundaryUnchecked(Node node,
            CraftingSimulationState parent, long requestedAmount,
            PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        // AE2 reports a fuzzy_crafted_input when it has already selected a valid
        // substitute craftable durability tool instead of the pattern's first key.
        // Let the boundary verifier prove the selected key and every visible
        // template have the same deterministic remainder behavior before the
        // request is batched; all other fuzzy cases still fail those checks.
        if (!isReusableBoundaryReason(node.barrierReason)) {
            return rejectReusableBoundary(node, null,
                    "unsupported_barrier:" + node.barrierReason, null);
        }

        var nodeBridge = (OmniCraftingTreeNodeBridge) node.occurrences.getFirst();
        List<CraftingTreeProcess> processes = nodeBridge.molecularmanipulator$getProcesses();
        if (processes == null || processes.size() != 1) {
            return rejectReusableBoundary(node, null, "pattern_candidate_count", null);
        }
        if (nodeBridge.molecularmanipulator$canEmit()) {
            return rejectReusableBoundary(node, null, "emitting_boundary", null);
        }

        var process = (OmniCraftingTreeProcessBridge) processes.getFirst();
        if (!process.molecularmanipulator$hasContainerItems()) {
            return rejectReusableBoundary(node, null, "container_flag_missing", null);
        }

        IPatternDetails details = process.molecularmanipulator$getDetails();
        if (details == null) {
            return rejectReusableBoundary(node, null, "missing_pattern_details", null);
        }
        IPatternDetails.IInput[] inputs = details.getInputs();
        Map<CraftingTreeNode, Long> childNodes = process.molecularmanipulator$getChildNodes();
        if (inputs == null || childNodes == null || inputs.length != childNodes.size()) {
            return rejectReusableBoundary(node, details, "dynamic_input_layout", null);
        }
        long outputPerPattern = 0;
        for (var output : details.getOutputs()) {
            if (output == null || output.what() == null || output.amount() <= 0) {
                return rejectReusableBoundary(node, details, "invalid_output", null);
            }
            if (!node.key.equals(output.what())) {
                return rejectReusableBoundary(node, details, "secondary_output", null);
            }
            outputPerPattern = saturatedAdd(outputPerPattern, output.amount());
        }
        if (outputPerPattern <= 0) {
            return rejectReusableBoundary(node, details, "missing_primary_output", null);
        }

        var attempt = new ChildCraftingSimulationState(parent);
        attempt.addStackBytes(node.key, node.amount, requestedAmount);
        long remainingAmount = requestedAmount;
        for (InputTemplate template : nodeBridge.molecularmanipulator$getValidItemTemplates(attempt)) {
            long extracted = extractTemplateMultipliers(attempt, template, remainingAmount);
            remainingAmount -= extracted;
            if (remainingAmount == 0) {
                attempt.applyDiff(parent);
                return true;
            }
        }

        if (outputPerPattern <= 0) {
            return rejectReusableBoundary(node, details, "invalid_output_per_pattern", null);
        }
        long totalRequestedItems = saturatedMultiply(node.amount, remainingAmount);
        long patternTimes = ceilDiv(totalRequestedItems, outputPerPattern);
        var inputPlans = new ArrayList<BoundaryInputPlan>(inputs.length);
        boolean fuzzyCraftedBoundary = "fuzzy_crafted_input".equals(node.barrierReason);
        int selectedSubstituteInputs = 0;
        int inputIndex = 0;
        for (var entry : childNodes.entrySet()) {
            checkpoint(pauseCheckpoint);
            CraftingTreeNode child = entry.getKey();
            var childBridge = (OmniCraftingTreeNodeBridge) child;
            IPatternDetails.IInput input = inputs[inputIndex++];
            if (!sameInputSemantics(
                    childBridge.molecularmanipulator$getParentInput(), input)) {
                return rejectReusableBoundary(node, details, "dynamic_input_identity", null);
            }
            if (!input.isValid(
                    childBridge.molecularmanipulator$getWhat(),
                    childBridge.molecularmanipulator$getLevel())) {
                return rejectReusableBoundary(node, details,
                        "selected_input_not_valid", null);
            }
            if (node.key.equals(childBridge.molecularmanipulator$getWhat())) {
                return rejectReusableBoundary(node, details, "self_referencing_input", null);
            }

            GenericStack primaryInput = getPrimaryInputChoice(input);
            boolean selectedSubstitute = primaryInput == null
                    || !primaryInput.what().equals(
                            childBridge.molecularmanipulator$getWhat())
                    || primaryInput.amount()
                            != childBridge.molecularmanipulator$getAmount();

            BoundaryInputClassification classification = classifyBoundaryInput(
                    input, childBridge, attempt, pauseCheckpoint);
            if (classification.rejectionReason() != null) {
                return rejectReusableBoundary(node, details, classification.rejectionReason(), null);
            }
            BoundaryInputMode mode = classification.mode();

            long multiplier = input.getMultiplier();
            if (multiplier <= 0 || entry.getValue() == null || entry.getValue() != multiplier) {
                return rejectReusableBoundary(node, details, "invalid_input_multiplier", null);
            }
            if (selectedSubstitute) {
                selectedSubstituteInputs++;
                if (!OmniMaxFastExecutionPolicy
                        .mayBatchDeterministicDamageSubstitute(
                                node.barrierReason,
                                mode == BoundaryInputMode.DETERMINISTIC_DAMAGE,
                                multiplier,
                                childBridge.molecularmanipulator$getAmount())) {
                    return rejectReusableBoundary(node, details,
                            "unsupported_selected_substitute", null);
                }
            }
            if (mode == BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                if (multiplier <= 0) {
                    return rejectReusableBoundary(node, details,
                            "unsupported_damage_input_multiplier", null);
                }
            }
            long childRequest = switch (mode) {
                case INVARIANT_REUSABLE -> multiplier;
                case CONSUMABLE -> saturatedMultiply(multiplier, patternTimes);
                case DETERMINISTIC_DAMAGE -> 0;
                case UNSAFE -> throw new IllegalStateException(
                        "Unsafe reusable boundary input escaped classification");
            };
            inputPlans.add(new BoundaryInputPlan(
                    input, childBridge, mode, childRequest, multiplier));
        }
        if (fuzzyCraftedBoundary && selectedSubstituteInputs != 1) {
            return rejectReusableBoundary(node, details,
                    "ambiguous_selected_substitute_count", null);
        }

        var containerItems = new KeyCounter();
        // Resolve the speculative durability optimization before issuing any
        // ordinary child request. A rejected durability boundary must not leak
        // missing-item accounting into AE2 before the native planner fallback.
        for (BoundaryInputPlan inputPlan : inputPlans) {
            if (inputPlan.mode == BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                if (!allocateDeterministicDamageInput(attempt, inputPlan.input,
                        inputPlan.child, inputPlan.multiplier, patternTimes,
                        pauseCheckpoint)) {
                    return rejectReusableBoundary(node, details,
                            "insufficient_deterministic_damage_capacity", null);
                }
            }
        }
        for (BoundaryInputPlan inputPlan : inputPlans) {
            if (inputPlan.mode != BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                if (inputPlan.mode == BoundaryInputMode.INVARIANT_REUSABLE) {
                    var returnedByInput = new KeyCounter();
                    inputPlan.child.molecularmanipulator$request(
                            attempt, inputPlan.requestedAmount, returnedByInput);
                    for (var stack : returnedByInput) {
                        long mergedAmount = checkedAdd(
                                containerItems.get(stack.getKey()),
                                stack.getLongValue(),
                                "reusable_return_count_overflow");
                        containerItems.set(stack.getKey(), mergedAmount);
                    }
                    try {
                        long additionalInputUses =
                                OmniMaxFastByteAccounting.additionalRepeatedVolume(
                                        inputPlan.multiplier, patternTimes);
                        if (additionalInputUses > 0) {
                            attempt.addStackBytes(
                                    inputPlan.child.molecularmanipulator$getWhat(),
                                    inputPlan.child.molecularmanipulator$getAmount(),
                                    additionalInputUses);
                        }
                        for (var stack : returnedByInput) {
                            long additionalReturns =
                                    OmniMaxFastByteAccounting.additionalRepeatedVolume(
                                            stack.getLongValue(), patternTimes);
                            if (additionalReturns > 0) {
                                attempt.addStackBytes(
                                        stack.getKey(), 1, additionalReturns);
                            }
                        }
                    } catch (ArithmeticException exception) {
                        throw new Fallback("reusable_byte_count_overflow");
                    }
                } else {
                    if (!OmniMaxFastExecutionPolicy.mayExecuteNativeBoundary(
                            inputPlan.child.molecularmanipulator$getAmount(),
                            inputPlan.requestedAmount,
                            MAX_LINEAR_NATIVE_BOUNDARY_ITEMS)
                            && !canSatisfyConsumableBoundaryInputFromStock(
                                    attempt, inputPlan, pauseCheckpoint)) {
                        return rejectReusableBoundary(
                                node,
                                details,
                                "oversized_recursive_consumable_input:"
                                        + inputPlan.child.molecularmanipulator$getWhat(),
                                null);
                    }
                    inputPlan.child.molecularmanipulator$request(
                            attempt, inputPlan.requestedAmount, containerItems);
                }
            }
        }

        for (var stack : containerItems) {
            attempt.insert(stack.getKey(), stack.getLongValue(), Actionable.MODULATE);
            attempt.addStackBytes(stack.getKey(), stack.getLongValue(), 1);
        }
        for (var output : details.getOutputs()) {
            attempt.insert(output.what(), saturatedMultiply(output.amount(), patternTimes),
                    Actionable.MODULATE);
        }
        attempt.addCrafting(details, patternTimes);
        attempt.addBytes(patternTimes);

        long produced = attempt.extract(node.key, totalRequestedItems, Actionable.MODULATE);
        if (produced != totalRequestedItems) {
            return rejectReusableBoundary(node, details, "produced_output_mismatch", null);
        }
        attempt.applyDiff(parent);
        if (Config.MAX_FAST_DIAGNOSTICS.get()) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST reusable boundary applied: key={}, amount={}, requests={}, patterns={}, barrier={}, pattern={}",
                    node.key, node.amount, requestedAmount, patternTimes,
                    node.barrierReason, describePattern(details));
        }
        return true;
    }

    private static boolean canSatisfyConsumableBoundaryInputFromStock(
            CraftingSimulationState inventory,
            BoundaryInputPlan inputPlan,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        if (inputPlan == null
                || inputPlan.mode != BoundaryInputMode.CONSUMABLE
                || inputPlan.requestedAmount <= 0) {
            return false;
        }
        var probe = new ChildCraftingSimulationState(inventory);
        long remaining = inputPlan.requestedAmount;
        try {
            for (InputTemplate template
                    : inputPlan.child.molecularmanipulator$getValidItemTemplates(probe)) {
                checkpoint(pauseCheckpoint);
                if (template == null || template.key() == null
                        || template.amount() <= 0
                        || classifyRemainingKey(
                                inputPlan.input,
                                template.key(),
                                inputPlan.child.molecularmanipulator$getLevel())
                                != BoundaryInputMode.CONSUMABLE) {
                    return false;
                }
                remaining -= extractTemplateMultipliers(
                        probe, template, remaining);
                if (remaining == 0) {
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            return false;
        }
        return false;
    }

    private static boolean rejectReusableBoundary(Node node, IPatternDetails details,
            String reason, RuntimeException exception) {
        if (Config.MAX_FAST_DIAGNOSTICS.get()) {
            String pattern = describePattern(details);
            if (exception == null) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST reusable boundary fallback: key={}, amount={}, barrier={}, pattern={}, reason={}",
                        node.key, node.amount, node.barrierReason, pattern, reason);
            } else {
                AppliedEnhancements.LOGGER.warn(
                        "Omni MAX_FAST reusable boundary failed: key={}, amount={}, barrier={}, pattern={}, reason={}",
                        node.key, node.amount, node.barrierReason, pattern, reason, exception);
            }
        }
        return false;
    }

    private static String describePattern(IPatternDetails details) {
        if (details == null) {
            return "unknown";
        }
        try {
            return String.valueOf(details.getDefinition());
        } catch (RuntimeException exception) {
            return details.getClass().getName();
        }
    }

    private static BoundaryInputClassification classifyBoundaryInput(IPatternDetails.IInput input,
            OmniCraftingTreeNodeBridge child, CraftingSimulationState inventory,
            PauseCheckpoint pauseCheckpoint)
            throws InterruptedException {
        BoundaryInputMode mode = classifyRemainingKey(
                input, child.molecularmanipulator$getWhat(),
                child.molecularmanipulator$getLevel());
        if (mode == BoundaryInputMode.UNSAFE) {
            return BoundaryInputClassification.rejected(
                    "unsupported_container_transition");
        }
        for (InputTemplate template : child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
            BoundaryInputMode templateMode = classifyRemainingKey(
                    input, template.key(), child.molecularmanipulator$getLevel());
            if (templateMode == BoundaryInputMode.UNSAFE) {
                return BoundaryInputClassification.rejected(
                        "unsupported_container_transition");
            }
            if (templateMode != mode) {
                return BoundaryInputClassification.rejected(
                        "mixed_container_transition_modes");
            }
        }
        return BoundaryInputClassification.accepted(mode);
    }

    private static BoundaryInputMode classifyRemainingKey(IPatternDetails.IInput input,
            AEKey key, net.minecraft.world.level.Level level) {
        var analysis = MolecularReusableInputAdapters.analyze(input, key, level, 2);
        return switch (analysis.mode()) {
            case CONSUMABLE -> BoundaryInputMode.CONSUMABLE;
            case INVARIANT_REUSABLE -> BoundaryInputMode.INVARIANT_REUSABLE;
            case DETERMINISTIC_DAMAGE -> BoundaryInputMode.DETERMINISTIC_DAMAGE;
            case UNSUPPORTED -> BoundaryInputMode.UNSAFE;
        };
    }

    /**
     * Reserves real finite-durability tools for a pattern boundary.
     *
     * <p>Each selected group contains {@code inputMultiplier} tools with the
     * exact same AE key, so a single pattern extraction never has to mix damage
     * states. Existing tools contribute their proven remaining capacity first.
     * If that is insufficient, the fresh primary tool is requested in one
     * recursive batch for the remaining capacity instead of returning to AE2's
     * one-pattern-at-a-time container loop.</p>
     *
     * <p>Multi-tool pool extension: When multiple tools of the same base type
     * but different durabilities exist in the network, calculate total capacity
     * across all instances and batch the entire request if capacity allows.</p>
     *
     * <p>We intentionally do not credit the final damaged tools back into the
     * planning inventory: execution may choose another valid damage state, and
     * omitting those remainders is conservative while the real CPU still
     * returns every exact remainder produced by the recipe. Input and remainder
     * byte costs are nevertheless recorded for every logical use.</p>
     */
    private static boolean allocateDeterministicDamageInput(
            CraftingSimulationState inventory, IPatternDetails.IInput input,
            OmniCraftingTreeNodeBridge child, long inputMultiplier,
            long patternTimes, PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        if (inputMultiplier <= 0 || patternTimes <= 0
                || child.molecularmanipulator$getAmount() != 1) {
            return false;
        }

        // Multi-tool pool: collect all available tools with their capacities
        var toolPool = new ArrayList<ToolInstance>();
        var seenKeys = new HashSet<AEKey>();
        long totalCapacity = 0;

        for (InputTemplate template : child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
            if (template == null || template.key() == null || template.amount() != 1) {
                return false;
            }
            if (!seenKeys.add(template.key())) {
                continue;
            }

            long available = inventory.extract(
                    template.key(), Long.MAX_VALUE, Actionable.SIMULATE);
            long availableGroups = available / inputMultiplier;
            if (availableGroups <= 0) {
                continue;
            }

            var analysis = MolecularReusableInputAdapters.analyze(
                    input, template.key(), child.molecularmanipulator$getLevel(),
                    patternTimes);
            if (analysis.mode()
                    != MolecularReusableInputAdapters.Mode.DETERMINISTIC_DAMAGE
                    || analysis.safeCrafts() <= 0) {
                return false;
            }

            long toolCapacity = saturatedMultiply(availableGroups, analysis.safeCrafts());
            totalCapacity = saturatedAdd(totalCapacity, toolCapacity);

            toolPool.add(new ToolInstance(
                    template.key(),
                    availableGroups,
                    analysis.safeCrafts(),
                    toolCapacity,
                    inputMultiplier));
        }

        // Check if total capacity meets the request
        if (totalCapacity < patternTimes) {
            // Not enough capacity, fall back to single-tool path
            return allocateDeterministicDamageInputLegacy(
                    inventory, input, child, inputMultiplier, patternTimes, pauseCheckpoint);
        }

        // Multi-tool pool path: allocate from pool
        long remainingPatterns = patternTimes;
        for (ToolInstance tool : toolPool) {
            if (remainingPatterns == 0) {
                break;
            }

            long patternsFromThisTool = Math.min(remainingPatterns, tool.capacity);
            long groupsNeeded = ceilDiv(patternsFromThisTool, tool.safeCrafts);
            long selectedGroups = Math.min(tool.availableGroups, groupsNeeded);

            long toolAmount;
            try {
                toolAmount = Math.multiplyExact(selectedGroups, tool.inputMultiplier);
            } catch (ArithmeticException exception) {
                return false;
            }

            long extracted = inventory.extract(tool.key, toolAmount, Actionable.MODULATE);
            if (extracted != toolAmount) {
                return false;
            }

            long craftedFromThisExtraction = Math.min(
                    patternsFromThisTool,
                    saturatedMultiply(selectedGroups, tool.safeCrafts));
            remainingPatterns -= craftedFromThisExtraction;
        }

        if (remainingPatterns != 0) {
            return false;
        }

        long logicalUses;
        try {
            logicalUses = OmniMaxFastByteAccounting.totalLogicalVolume(
                    inputMultiplier, patternTimes);
        } catch (ArithmeticException exception) {
            return false;
        }
        if (logicalUses > 0) {
            // Every logical use contributes one input stack and one damaged
            // remainder stack, even though the physical tools are leased once.
            inventory.addStackBytes(
                    child.molecularmanipulator$getWhat(), 1, logicalUses);
            inventory.addStackBytes(
                    child.molecularmanipulator$getWhat(), 1, logicalUses);
        }

        if (Config.MAX_FAST_DIAGNOSTICS.get() && toolPool.size() > 1) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST multi-tool pool batch: patterns={}, tools={}, totalCapacity={}",
                    patternTimes, toolPool.size(), totalCapacity);
        }

        return true;
    }

    private static boolean allocateDeterministicDamageInputLegacy(
            CraftingSimulationState inventory, IPatternDetails.IInput input,
            OmniCraftingTreeNodeBridge child, long inputMultiplier,
            long patternTimes, PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        var selections = new ArrayList<FiniteToolSelection>();
        var seenKeys = new HashSet<AEKey>();
        long remainingPatterns = patternTimes;

        for (InputTemplate template : child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
            if (remainingPatterns == 0) {
                break;
            }
            if (template == null || template.key() == null || template.amount() != 1) {
                return false;
            }
            if (!seenKeys.add(template.key())) {
                continue;
            }

            long available = inventory.extract(
                    template.key(), Long.MAX_VALUE, Actionable.SIMULATE);
            long availableGroups = available / inputMultiplier;
            if (availableGroups <= 0) {
                continue;
            }

            var analysis = MolecularReusableInputAdapters.analyze(
                    input, template.key(), child.molecularmanipulator$getLevel(),
                    remainingPatterns);
            if (analysis.mode()
                    != MolecularReusableInputAdapters.Mode.DETERMINISTIC_DAMAGE
                    || analysis.safeCrafts() <= 0) {
                return false;
            }

            long groupsNeeded = ceilDiv(
                    remainingPatterns, analysis.safeCrafts());
            long selectedGroups = Math.min(availableGroups, groupsNeeded);
            long toolAmount;
            try {
                toolAmount = Math.multiplyExact(
                        selectedGroups, inputMultiplier);
            } catch (ArithmeticException exception) {
                return false;
            }

            selections.add(new FiniteToolSelection(
                    template.key(), toolAmount));
            long coveredPatterns = saturatedMultiply(
                    selectedGroups, analysis.safeCrafts());
            long usedPatterns = Math.min(remainingPatterns, coveredPatterns);
            remainingPatterns -= usedPatterns;
        }

        long newToolAmount = 0;
        if (remainingPatterns > 0) {
            AEKey freshTool = child.molecularmanipulator$getWhat();
            var freshAnalysis = MolecularReusableInputAdapters.analyze(
                    input, freshTool, child.molecularmanipulator$getLevel(),
                    remainingPatterns);
            if (freshAnalysis.mode()
                    != MolecularReusableInputAdapters.Mode.DETERMINISTIC_DAMAGE
                    || freshAnalysis.safeCrafts() <= 0) {
                return false;
            }

            long newToolGroups = ceilDiv(
                    remainingPatterns, freshAnalysis.safeCrafts());
            try {
                newToolAmount = Math.multiplyExact(
                        newToolGroups, inputMultiplier);
            } catch (ArithmeticException exception) {
                return false;
            }
        }

        long logicalUses;
        try {
            logicalUses = Math.multiplyExact(inputMultiplier, patternTimes);
        } catch (ArithmeticException exception) {
            return false;
        }
        if (newToolAmount > logicalUses) {
            return false;
        }

        for (FiniteToolSelection selection : selections) {
            long extracted = inventory.extract(
                    selection.key, selection.amount, Actionable.MODULATE);
            if (extracted != selection.amount) {
                throw new IllegalStateException(
                        "Crafting simulation inventory changed during finite-tool extraction");
            }
        }

        if (newToolAmount > 0) {
            child.molecularmanipulator$request(inventory, newToolAmount, null);
        }

        // Requesting newly crafted tools already charged their first logical
        // input use. Existing tools and all later reuses still need that cost.
        long additionalInputUses = logicalUses - newToolAmount;
        if (additionalInputUses > 0) {
            inventory.addStackBytes(
                    child.molecularmanipulator$getWhat(), 1,
                    additionalInputUses);
        }

        // Runtime is allowed to choose another valid damage-state ordering.
        // Charge the maximum possible remainder volume so CPU storage is never
        // underestimated even when fewer tools actually break than planned.
        if (logicalUses > 0) {
            inventory.addStackBytes(
                    child.molecularmanipulator$getWhat(), 1, logicalUses);
        }
        return true;
    }

    private static boolean leaseInvariantReusableInput(
            CraftingSimulationState inventory, GraphReusableInput reusableInput,
            long patternTimes, KeyCounter returned,
            PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        if (reusableInput.multiplier <= 0
                || patternTimes <= 0
                || reusableInput.child.molecularmanipulator$getAmount() != 1) {
            return false;
        }

        long remaining = reusableInput.multiplier;
        var selected = new KeyCounter();
        for (InputTemplate template
                : reusableInput.child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
            if (remaining == 0) {
                break;
            }
            if (template == null || template.key() == null || template.amount() != 1) {
                return false;
            }
            long available = inventory.extract(
                    template.key(), Long.MAX_VALUE, Actionable.SIMULATE);
            available = Math.max(0, available - selected.get(template.key()));
            long selectedAmount = Math.min(remaining, available);
            if (selectedAmount == 0) {
                continue;
            }
            var analysis = MolecularReusableInputAdapters.analyze(
                    reusableInput.input, template.key(),
                    reusableInput.child.molecularmanipulator$getLevel(), 2);
            if (analysis.mode()
                    != MolecularReusableInputAdapters.Mode.INVARIANT_REUSABLE
                    || !template.key().equals(analysis.finalKey())) {
                return false;
            }
            selected.add(template.key(), selectedAmount);
            remaining -= selectedAmount;
        }
        for (var selection : selected) {
            checkpoint(pauseCheckpoint);
            long extracted = inventory.extract(
                    selection.getKey(), selection.getLongValue(), Actionable.MODULATE);
            if (extracted != selection.getLongValue()) {
                throw new IllegalStateException(
                        "Crafting simulation inventory changed during reusable extraction");
            }
            long returnedAmount = checkedAdd(
                    returned.get(selection.getKey()), extracted,
                    "reusable_return_count_overflow");
            returned.set(selection.getKey(), returnedAmount);
        }

        long freshlyRequested = remaining;
        if (freshlyRequested > 0) {
            if (!OmniMaxFastExecutionPolicy.mayExecuteNativeBoundary(
                    reusableInput.child.molecularmanipulator$getAmount(),
                    freshlyRequested,
                    MAX_LINEAR_NATIVE_BOUNDARY_ITEMS)) {
                return false;
            }
            reusableInput.child.molecularmanipulator$request(
                    inventory, freshlyRequested, returned);
        }

        long logicalUses = checkedMultiply(
                reusableInput.multiplier, patternTimes,
                "reusable_input_bytes_overflow");
        long additionalLogicalUses = Math.max(0, logicalUses - freshlyRequested);
        if (additionalLogicalUses > 0) {
            inventory.addStackBytes(
                    reusableInput.child.molecularmanipulator$getWhat(), 1,
                    additionalLogicalUses);
        }
        return true;
    }

    private static long extractTemplateMultipliers(CraftingSimulationState inventory,
            InputTemplate template, long requestedMultipliers) {
        long extractLimit = saturatedMultiply(template.amount(), requestedMultipliers);
        long available = inventory.extract(template.key(), extractLimit, Actionable.SIMULATE);
        long extractedMultipliers = Math.min(requestedMultipliers, available / template.amount());
        if (extractedMultipliers <= 0) {
            return 0;
        }
        long extractedAmount = template.amount() * extractedMultipliers;
        long extracted = inventory.extract(template.key(), extractedAmount, Actionable.MODULATE);
        if (extracted != extractedAmount) {
            throw new IllegalStateException("Crafting simulation inventory changed during template extraction");
        }
        return extractedMultipliers;
    }

    /**
     * Mirrors the inventory-collection phase of AE2's CraftingTreeNode for a
     * consumable pattern slot with substitutions. The selected child key is
     * crafted only after every currently available valid template has been
     * consumed in AE2's original order.
     */
    private static long extractConsumableInputTemplates(Node node,
            CraftingSimulationState inventory, GraphConsumableInput requestInput,
            long requestedMultipliers, long requestedItems,
            PauseCheckpoint pauseCheckpoint)
            throws Fallback, InterruptedException {
        if (requestInput.childIndex != node.index
                || requestInput.input == null
                || requestInput.child == null
                || !node.key.equals(requestInput.child.molecularmanipulator$getWhat())
                || node.amount != requestInput.child.molecularmanipulator$getAmount()) {
            throw new Fallback("invalid_substitute_input_context");
        }

        if (!requestInput.substituteInput) {
            for (InputTemplate template
                    : requestInput.child.molecularmanipulator$getValidItemTemplates(inventory)) {
                checkpoint(pauseCheckpoint);
                if (template == null || !node.key.equals(template.key())
                        || template.amount() != node.amount) {
                    throw new Fallback("fuzzy_or_contextual_input");
                }
            }
            long available = inventory.extract(
                    node.key, requestedItems, Actionable.SIMULATE);
            long extractedMultipliers = Math.min(
                    requestedMultipliers, available / node.amount);
            if (extractedMultipliers > 0) {
                long extractedAmount = node.amount * extractedMultipliers;
                long extracted = inventory.extract(
                        node.key, extractedAmount, Actionable.MODULATE);
                if (extracted != extractedAmount) {
                    throw new IllegalStateException(
                            "Crafting simulation inventory changed during exact extraction");
                }
            }
            return requestedMultipliers - extractedMultipliers;
        }

        long startedAt = Config.MAX_FAST_DIAGNOSTICS.get()
                ? System.nanoTime()
                : 0;
        long remaining = requestedMultipliers;
        int templateCount = 0;
        for (InputTemplate template
                : requestInput.child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
            templateCount++;
            if (template == null || template.key() == null || template.amount() <= 0) {
                throw new Fallback("invalid_substitute_input_template");
            }
            if (classifyRemainingKey(
                    requestInput.input, template.key(),
                    requestInput.child.molecularmanipulator$getLevel())
                    != BoundaryInputMode.CONSUMABLE) {
                throw new Fallback("unsafe_substitute_input_template");
            }

            long extracted = extractTemplateMultipliers(
                    inventory, template, remaining);
            remaining -= extracted;
            if (remaining == 0) {
                break;
            }
        }

        if (Config.MAX_FAST_DIAGNOSTICS.get()) {
            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST substitute input batch: key={}, amount={}, requested={}, extracted={}, remaining={}, templates={}, selectMs={}",
                    node.key, node.amount, requestedMultipliers,
                    requestedMultipliers - remaining, remaining, templateCount,
                    (System.nanoTime() - startedAt) / 1_000_000.0);
        }
        return remaining;
    }

    private enum BoundaryInputMode {
        CONSUMABLE,
        INVARIANT_REUSABLE,
        DETERMINISTIC_DAMAGE,
        UNSAFE
    }

    private record BoundaryInputClassification(BoundaryInputMode mode, String rejectionReason) {
        private static BoundaryInputClassification accepted(BoundaryInputMode mode) {
            return new BoundaryInputClassification(mode, null);
        }

        private static BoundaryInputClassification rejected(String reason) {
            return new BoundaryInputClassification(null, reason);
        }
    }

    private record BoundaryInputPlan(IPatternDetails.IInput input,
            OmniCraftingTreeNodeBridge child, BoundaryInputMode mode,
            long requestedAmount, long multiplier) {
    }

    private record GraphReusableInput(IPatternDetails.IInput input,
            OmniCraftingTreeNodeBridge child, BoundaryInputMode mode,
            long multiplier) {
    }

    private record GraphConsumableInput(IPatternDetails.IInput input,
            OmniCraftingTreeNodeBridge child, int childIndex, long multiplier,
            boolean substituteInput) {
    }

    /**
     * Gives context-split nodes a stable material-service identity. AE2's
     * IPatternDetails contract requires equals/hashCode to identify the same
     * encoded pattern across decoding instances, so candidate identities must
     * be value-based rather than reference-based.
     */
    private static final class SimulationServiceIds {
        private final Map<AEKey, Integer> terminalIds =
                new HashMap<>();
        private final Map<IPatternDetails,
                Map<SimulationServiceRequest, Integer>> candidateIds =
                        new HashMap<>();
        private int nextId;

        private int terminal(AEKey key) {
            return terminalIds.computeIfAbsent(
                    key,
                    ignored -> nextId++);
        }

        private int candidate(IPatternDetails details, AEKey key, long amount) {
            Map<SimulationServiceRequest, Integer> requests =
                    candidateIds.computeIfAbsent(
                            details, ignored -> new HashMap<>());
            return requests.computeIfAbsent(
                    new SimulationServiceRequest(key, amount),
                    ignored -> nextId++);
        }
    }

    private record OrderedGraphInput(GraphConsumableInput consumableInput,
            GraphReusableInput reusableInput) {
        private static OrderedGraphInput consumable(GraphConsumableInput consumableInput) {
            return new OrderedGraphInput(consumableInput, null);
        }

        private static OrderedGraphInput reusable(GraphReusableInput reusableInput) {
            return new OrderedGraphInput(null, reusableInput);
        }

        private boolean reusable() {
            return reusableInput != null;
        }
    }

    private record FiniteToolSelection(AEKey key, long amount) {
    }

    private record ToolInstance(AEKey key, long availableGroups, long safeCrafts,
            long capacity, long inputMultiplier) {
    }

    private static void validateTemplates(Node node, CraftingSimulationState inventory,
            PauseCheckpoint pauseCheckpoint)
            throws Fallback, InterruptedException {
        for (CraftingTreeNode occurrence : node.occurrences) {
            checkpoint(pauseCheckpoint);
            var bridge = (OmniCraftingTreeNodeBridge) occurrence;
            Iterable<InputTemplate> templates = bridge.molecularmanipulator$getValidItemTemplates(inventory);
            for (InputTemplate template : templates) {
                if (!node.key.equals(template.key()) || template.amount() != node.amount) {
                    throw new Fallback("fuzzy_or_contextual_input");
                }
            }
        }
    }

    private static final class Compiler {
        private final int maxNodes;
        private long deadline;
        private final PauseCheckpoint pauseCheckpoint;
        private final List<Node> nodes = new ArrayList<>();
        private final Map<NodeKey, Integer> nodeIndexes = new HashMap<>();
        private final Map<NodeKey, IdentityHashMap<CraftingTreeNode, Integer>>
                splitNodeIndexes = new HashMap<>();
        private final Map<IPatternDetails, NodeKey> patternOwners = new IdentityHashMap<>();
        private final Map<AEKey, KeyContextBehavior> keyContextBehaviors = new HashMap<>();
        private final Set<AEKey> crossAmountContextSensitiveKeys = new HashSet<>();
        private final ArrayDeque<Integer> pendingInspections = new ArrayDeque<>();
        private final Set<AEKey> contextSplitKeys;
        private final ProgressSink progressSink;
        private long mergedOccurrences;
        private long pausedNanos;
        private int orderedChoiceCount;
        private boolean hasSubstituteInputs;
        private boolean hasReusableInputs;

        private Compiler(int maxNodes, long deadline, PauseCheckpoint pauseCheckpoint,
                Set<AEKey> contextSplitKeys, ProgressSink progressSink) {
            this.maxNodes = maxNodes;
            this.deadline = deadline;
            this.pauseCheckpoint = pauseCheckpoint;
            this.contextSplitKeys = contextSplitKeys;
            this.progressSink = progressSink;
        }

        private Graph compile(CraftingTreeNode root)
                throws Fallback, ContextSplit, InterruptedException {
            int rootIndex = intern(root, RecipeContext.ROOT);
            nodes.get(rootIndex).reachable = true;
            // Interning a later branch may add another recursion context to a
            // node that was already inspected. Drain dirty nodes to a fixed
            // point so every merged occurrence and its descendants are proven
            // equivalent before the graph can execute.
            while (!pendingInspections.isEmpty()) {
                checkBudget();
                int index = pendingInspections.removeFirst();
                Node node = nodes.get(index);
                node.inspectionQueued = false;
                if (!node.reachable) {
                    continue;
                }
                try {
                    inspectPendingOccurrences(node);
                } catch (Barrier barrier) {
                    node.barrier = true;
                    node.barrierReason = barrier.reason;
                    node.inspectedOccurrences = node.occurrences.size();
                    if (requiresImmediateFallback(barrier.reason)) {
                        if (Config.MAX_FAST_DIAGNOSTICS.get()) {
                            AppliedEnhancements.LOGGER.info(
                                    "Omni MAX_FAST compile-time fallback: key={}, amount={}, barrier={}, pattern={}",
                                    node.key, node.amount, node.barrierReason,
                                    describePattern(node.details));
                        }
                        throw new Fallback("unsafe_pattern_boundary:" + barrier.reason);
                    }
                }
            }

            validateReusableGraphConflicts();
            validateQuantityFeedbackIsolation();

            int[] topologicalOrder = buildTopologicalOrder();
            long logicalNodeCount = countLogicalNodes(rootIndex, topologicalOrder);
            int barrierCount = 0;

            // The single MAX_FAST policy keeps compatible nodes in the fast
            // graph and relies on local runtime barriers for unsupported work.

            for (Node node : nodes) {
                if (node.reachable && node.barrier) {
                    barrierCount++;
                    // Default barrier nodes to hybrid if not already marked for full fallback
                    if (node.executionMode == ExecutionMode.PURE_FAST) {
                        node.executionMode = ExecutionMode.HYBRID_BARRIER;
                    }
                }
            }
            return new Graph(List.copyOf(nodes), topologicalOrder, rootIndex,
                    logicalNodeCount, mergedOccurrences, barrierCount, orderedChoiceCount,
                    !contextSplitKeys.isEmpty()
                            || !crossAmountContextSensitiveKeys.isEmpty(),
                    hasSubstituteInputs, hasReusableInputs);
        }

        /**
         * Analyze the compiled graph to determine optimal execution modes.
         * Nodes that would benefit from native AE2 handling (e.g., complex
         * multi-candidate patterns, recursive structures) are marked for
         * selective fallback while the rest uses fast path.
         */
        private void analyzeExecutionModes(int[] topologicalOrder) {
            for (int nodeIndex : topologicalOrder) {
                Node node = nodes.get(nodeIndex);
                if (!node.reachable) {
                    continue;
                }

                // Check if this node should use full fallback
                boolean needsFullFallback = false;
                String fallbackReason = null;

                // Criterion 1: Multiple candidate patterns with different output amounts
                if (node.candidatePatterns.size() > 1) {
                    long firstOutput = -1;
                    for (IPatternDetails pattern : node.candidatePatterns) {
                        long outputSum = 0;
                        for (var output : pattern.getOutputs()) {
                            if (output != null && node.key.equals(output.what())) {
                                outputSum += output.amount();
                            }
                        }
                        if (firstOutput < 0) {
                            firstOutput = outputSum;
                        } else if (firstOutput != outputSum) {
                            needsFullFallback = true;
                            fallbackReason = "variable_output_candidates";
                            break;
                        }
                    }
                }

                // Criterion 2: Deep barrier subtrees (>3 barriers in downstream)
                if (!needsFullFallback && node.barrier) {
                    int downstreamBarriers = countDownstreamBarriers(node, topologicalOrder);
                    if (downstreamBarriers > 3) {
                        needsFullFallback = true;
                        fallbackReason = "deep_barrier_subtree:" + downstreamBarriers;
                    }
                }

                // Criterion 3: Context-sensitive nodes with ordered choices
                if (!needsFullFallback && node.logicalOccurrences > 1) {
                    boolean hasOrderedChoices = false;
                    for (CraftingTreeNode occurrence : node.occurrences) {
                        var bridge = (OmniCraftingTreeNodeBridge) occurrence;
                        List<CraftingTreeProcess> processes = bridge.molecularmanipulator$getProcesses();
                        if (processes != null && processes.size() > 1) {
                            hasOrderedChoices = true;
                            break;
                        }
                    }
                    if (hasOrderedChoices) {
                        needsFullFallback = true;
                        fallbackReason = "multi_occurrence_ordered_choices";
                    }
                }

                if (needsFullFallback) {
                    // Keep a local hybrid boundary instead of forcing a full fallback.
                    // This allows MAX_FAST to try batch execution first, falling back
                    // to AE2 bridge only if needed, rather than skipping entirely
                    node.executionMode = ExecutionMode.HYBRID_BARRIER;
                    node.barrierReason = fallbackReason;

                    if (Config.MAX_FAST_DIAGNOSTICS.get()) {
                        AppliedEnhancements.LOGGER.info(
                                "Omni MAX_FAST hybrid barrier marked: key={}, amount={}, reason={}",
                                node.key, node.amount, fallbackReason);
                    }
                }
            }
        }

        /**
         * Count how many barrier nodes exist downstream of the given node.
         */
        private int countDownstreamBarriers(Node startNode, int[] topologicalOrder) {
            var visited = new HashSet<Integer>();
            var queue = new ArrayDeque<Integer>();
            queue.add(startNode.index);
            visited.add(startNode.index);
            int barrierCount = 0;

            while (!queue.isEmpty()) {
                int nodeIndex = queue.removeFirst();
                Node node = nodes.get(nodeIndex);

                if (node.barrier && nodeIndex != startNode.index) {
                    barrierCount++;
                }

                for (Edge edge : node.dependencyEdges) {
                    if (visited.add(edge.childIndex)) {
                        queue.add(edge.childIndex);
                    }
                }
            }

            return barrierCount;
        }

        /**
         * Aggregating all repetitions of an earlier consumable input can change
         * the amount of its surplus that is visible to a later reusable slot.
         * Reject that graph whenever any ordinary graph key is also a valid
         * candidate for a reusable input. Otherwise candidate availability is
         * unchanged by recursive planning, so leasing the first invariant
         * candidate once is equivalent to AE2 leasing and returning it once per
         * pattern execution.
         */
        private void validateReusableGraphConflicts()
                throws Fallback, InterruptedException {
            for (Node owner : nodes) {
                if (!owner.reachable || owner.reusableInputs.isEmpty()) {
                    continue;
                }
                for (GraphReusableInput reusableInput : owner.reusableInputs) {
                    for (Node graphNode : nodes) {
                        if (!graphNode.reachable) {
                            continue;
                        }
                        checkBudget();
                        try {
                            if (reusableInput.input.isValid(
                                    graphNode.key,
                                    reusableInput.child.molecularmanipulator$getLevel())) {
                                throw new Fallback("reusable_candidate_graph_conflict");
                            }
                        } catch (Fallback fallback) {
                            throw fallback;
                        } catch (RuntimeException exception) {
                            throw new Fallback("reusable_candidate_validation_error");
                        }
                    }
                }
            }
        }

        /**
         * A quantity-feedback pattern is only batch-equivalent while none of
         * its other inputs can inject the produced key back into the planning
         * inventory. Direct inputs were already proven exact and remainder-free
         * by {@link #compileCandidate}; this fixed-point graph pass extends that
         * proof through every non-feedback descendant. Any native boundary,
         * reusable/container input, substitute choice, incomplete candidate set
         * or descendant that produces the feedback key keeps AE2's native
         * quantity loop for this node.
         */
        private void validateQuantityFeedbackIsolation()
                throws Fallback, InterruptedException {
            for (Node owner : nodes) {
                if (!owner.reachable || owner.compiledCandidates.isEmpty()) {
                    continue;
                }
                for (CompiledCandidate candidate : owner.compiledCandidates) {
                    if (!candidate.quantityFeedbackBatch) {
                        continue;
                    }
                    if (isQuantityFeedbackIsolated(owner, candidate)) {
                        continue;
                    }

                    owner.barrier = true;
                    // This is deliberately not the ordinary quantity boundary:
                    // the failed isolation proof means callers must not treat
                    // this native subtree as deterministic for sparse/binary
                    // candidate allocation.
                    owner.barrierReason = "quantity_feedback_descendant_unsafe";
                    owner.executionMode = ExecutionMode.HYBRID_BARRIER;
                    owner.deterministicCandidateSubgraph = null;
                    if (Config.MAX_FAST_DIAGNOSTICS.get()) {
                        AppliedEnhancements.LOGGER.info(
                                "Omni MAX_FAST quantity feedback kept native: key={}, amount={}, reason=descendant_feedback_isolation",
                                owner.key, owner.amount);
                    }
                    break;
                }
            }
        }

        private boolean isQuantityFeedbackIsolated(
                Node owner, CompiledCandidate candidate)
                throws Fallback, InterruptedException {
            var pending = new ArrayDeque<Integer>();
            var visited = new HashSet<Integer>();
            var descendantOccurrences = new HashMap<AEKey, Integer>();
            int feedbackInputs = 0;

            for (OrderedGraphInput input : candidate.orderedInputs) {
                if (input.reusable()) {
                    return false;
                }
                GraphConsumableInput consumable = input.consumableInput;
                Node child = nodes.get(consumable.childIndex);
                if (child.key.equals(owner.key)) {
                    feedbackInputs++;
                    continue;
                }
                if (!OmniQuantityFeedbackBatch.isSafeDescendantInput(
                        new OmniQuantityFeedbackBatch.DescendantInputShape(
                                false, consumable.substituteInput, false))) {
                    return false;
                }
                Integer existingOccurrence = descendantOccurrences.putIfAbsent(
                        child.key, child.index);
                if (!OmniQuantityFeedbackBatch.isCompatibleDescendantOccurrence(
                        existingOccurrence, child.index)) {
                    return false;
                }
                if (!visited.add(child.index)) {
                    continue;
                }
                pending.addLast(child.index);
            }
            if (feedbackInputs != 1) {
                return false;
            }

            while (!pending.isEmpty()) {
                checkBudget();
                Node descendant = nodes.get(pending.removeFirst());
                boolean terminalOrEmitter = descendant.terminal || descendant.emitter;
                if (!OmniQuantityFeedbackBatch.isSafeDescendantNode(
                        new OmniQuantityFeedbackBatch.DescendantNodeShape(
                                descendant.key.equals(owner.key),
                                terminalOrEmitter, descendant.barrier,
                                !descendant.reusableInputs.isEmpty(),
                                descendant.allCandidatesCompiled,
                                descendant.compiledCandidates.size(),
                                descendant.candidatePatterns.size()))) {
                    return false;
                }
                if (terminalOrEmitter) {
                    continue;
                }

                for (CompiledCandidate descendantCandidate
                        : descendant.compiledCandidates) {
                    if (!OmniQuantityFeedbackBatch.isSafeDescendantCandidate(
                            new OmniQuantityFeedbackBatch.DescendantCandidateShape(
                                    descendantCandidate.hasContainerItems,
                                    descendantCandidate.limitsQuantity,
                                    descendantCandidate.quantityFeedbackBatch))) {
                        return false;
                    }
                    for (OrderedGraphInput input : descendantCandidate.orderedInputs) {
                        GraphConsumableInput consumable = input.consumableInput;
                        Node child = input.reusable()
                                ? null
                                : nodes.get(consumable.childIndex);
                        if (!OmniQuantityFeedbackBatch.isSafeDescendantInput(
                                new OmniQuantityFeedbackBatch.DescendantInputShape(
                                        input.reusable(),
                                        !input.reusable() && consumable.substituteInput,
                                        child != null && child.key.equals(owner.key)))) {
                            return false;
                        }
                        Integer existingOccurrence = descendantOccurrences.putIfAbsent(
                                child.key, child.index);
                        if (!OmniQuantityFeedbackBatch.isCompatibleDescendantOccurrence(
                                existingOccurrence, child.index)) {
                            return false;
                        }
                        if (visited.add(child.index)) {
                            pending.addLast(child.index);
                        }
                    }
                }
            }
            return true;
        }

        private int intern(CraftingTreeNode occurrence, RecipeContext context)
                throws Fallback, InterruptedException {
            checkBudget();
            var bridge = (OmniCraftingTreeNodeBridge) occurrence;
            AEKey key = bridge.molecularmanipulator$getWhat();
            long amount = bridge.molecularmanipulator$getAmount();
            if (key == null || amount <= 0) {
                throw new Fallback("invalid_node_template");
            }

            var nodeKey = new NodeKey(key, amount);
            var occurrenceContext = new OccurrenceContext(
                    context, bridge.molecularmanipulator$getParentInput());
            boolean splitByOccurrence = contextSplitKeys.contains(key);
            IdentityHashMap<CraftingTreeNode, Integer> occurrenceIndexes = splitByOccurrence
                    ? splitNodeIndexes.computeIfAbsent(
                            nodeKey, ignored -> new IdentityHashMap<>())
                    : null;
            Integer existing = splitByOccurrence
                    ? occurrenceIndexes.get(occurrence)
                    : nodeIndexes.get(nodeKey);
            if (existing != null) {
                Node node = nodes.get(existing);
                if (node.occurrenceSet.put(occurrence, Boolean.TRUE) == null) {
                    mergedOccurrences = saturatedAdd(mergedOccurrences, 1);
                    if (node.contextOccurrences.putIfAbsent(
                            occurrenceContext, occurrence) == null) {
                        node.occurrences.add(occurrence);
                        node.occurrenceContexts.add(context);
                        scheduleInspection(node);
                    }
                }
                return existing;
            }
            if (nodes.size() >= maxNodes) {
                throw new Fallback("node_limit");
            }

            int index = nodes.size();
            var node = new Node(index, key, amount, bridge.molecularmanipulator$getLevel());
            node.occurrences.add(occurrence);
            node.occurrenceContexts.add(context);
            node.occurrenceSet.put(occurrence, Boolean.TRUE);
            node.contextOccurrences.put(occurrenceContext, occurrence);
            nodes.add(node);
            progressSink.nodeDiscovered();
            if (splitByOccurrence) {
                occurrenceIndexes.put(occurrence, index);
            } else {
                nodeIndexes.put(nodeKey, index);
            }
            scheduleInspection(node);
            return index;
        }

        private void scheduleInspection(Node node) {
            if (!node.inspectionQueued) {
                node.inspectionQueued = true;
                pendingInspections.addLast(node.index);
            }
        }

        private void inspectPendingOccurrences(Node node)
                throws Fallback, Barrier, ContextSplit, InterruptedException {
            if (node.barrier) {
                node.inspectedOccurrences = node.occurrences.size();
                return;
            }
            while (node.inspectedOccurrences < node.occurrences.size()) {
                checkBudget();
                int occurrenceIndex = node.inspectedOccurrences;
                CraftingTreeNode occurrence = node.occurrences.get(occurrenceIndex);
                RecipeContext context = node.occurrenceContexts.get(occurrenceIndex);
                if (node.inspectedOccurrences == 0) {
                    inspect(node, occurrence, context);
                } else {
                    validateOccurrence(node, occurrence, context);
                }
                node.inspectedOccurrences++;
                progressSink.compilationStep();
            }
        }

        private void inspect(Node node, CraftingTreeNode occurrence, RecipeContext context)
                throws Fallback, Barrier, ContextSplit, InterruptedException {
            var nodeBridge = (OmniCraftingTreeNodeBridge) occurrence;
            if (nodeBridge.molecularmanipulator$canEmit()) {
                node.emitter = true;
                recordKeyContextBehavior(node, context, true, List.of());
                return;
            }

            nodeBridge.molecularmanipulator$buildChildPatterns();
            List<CraftingTreeProcess> processes = nodeBridge.molecularmanipulator$getProcesses();
            if (processes == null) {
                throw new Fallback("missing_process_state");
            }
            processes = getProgressCandidateProcesses(node, processes, true);
            if (processes.isEmpty()) {
                recordKeyContextBehavior(node, context, false, List.of());
                node.terminal = true;
                return;
            }
            node.candidatePatterns = getCandidatePatterns(processes);
            recordKeyContextBehavior(
                    node, context, false, node.candidatePatterns);
            if (processes.size() > 1) {
                // Candidate order depends on the live planning inventory. Keep this node as a
                // local native boundary so upstream requests are aggregated without forcing the
                // entire compiled graph through depth-first transactional execution.
                orderedChoiceCount++;
                node.executionMode = ExecutionMode.HYBRID_BARRIER;
                node.barrierReason = "ordered_pattern_choices";
            }

            node.compiledCandidates.add(compileCandidate(
                    node, processes.getFirst(), context, 0, true));
            node.allCandidatesCompiled = processes.size() == 1;

            if (processes.size() > 1) {
                node.allCandidatesCompiled = true;
                for (int candidateIndex = 1;
                        candidateIndex < processes.size(); candidateIndex++) {
                    try {
                        node.compiledCandidates.add(compileCandidate(
                                node, processes.get(candidateIndex), context,
                                candidateIndex, false));
                    } catch (Barrier barrier) {
                        node.allCandidatesCompiled = false;
                        node.candidateCompileFailures.put(
                                candidateIndex, barrier.reason);
                        if (Config.MAX_FAST_DIAGNOSTICS.get()) {
                            AppliedEnhancements.LOGGER.info(
                                    "Omni MAX_FAST candidate compile skipped: key={}, candidate={}, candidates={}, reason={}",
                                    node.key, candidateIndex, processes.size(), barrier.reason);
                        }
                        break;
                    }
                }
            }
        }

        private CompiledCandidate compileCandidate(Node node,
                CraftingTreeProcess candidateProcess, RecipeContext context,
                int sourceIndex, boolean primary)
                throws Fallback, Barrier, ContextSplit, InterruptedException {
            var process = (OmniCraftingTreeProcessBridge) candidateProcess;
            boolean hasContainerItems = process.molecularmanipulator$hasContainerItems();
            boolean limitsQuantity = process.molecularmanipulator$limitsQuantity();
            boolean quantityFeedbackCandidate = limitsQuantity && !hasContainerItems;

            IPatternDetails details = process.molecularmanipulator$getDetails();
            String patternBarrierReason = getPatternBarrierReason(details);
            if (patternBarrierReason != null) {
                if (quantityFeedbackCandidate) {
                    // Unknown or stateful pattern implementations are outside
                    // the feedback batching proof and retain AE2's native loop.
                    throw new Barrier("quantity_limited_pattern");
                } else if (patternBarrierReason.startsWith("unknown_pattern_type:")) {
                    if (primary && node.candidatePatterns.size() == 1) {
                        node.barrier = true;
                        node.barrierReason = patternBarrierReason;
                    }
                } else {
                    throw new Barrier(patternBarrierReason);
                }
            }

            long outputPerPattern = 0;
            int outputEntryCount = 0;
            for (var output : details.getOutputs()) {
                outputEntryCount++;
                if (output == null || output.what() == null || output.amount() <= 0) {
                    throw new Barrier("invalid_pattern_output");
                }
                if (!node.key.equals(output.what())) {
                    throw new Barrier("secondary_or_fuzzy_output");
                }
                outputPerPattern = checkedAdd(
                        outputPerPattern, output.amount(), "output_count_overflow");
            }
            if (outputPerPattern <= 0) {
                throw new Barrier("missing_primary_output");
            }

            IPatternDetails.IInput[] inputs = details.getInputs();
            Map<CraftingTreeNode, Long> childNodes = process.molecularmanipulator$getChildNodes();
            if (inputs == null || childNodes == null || inputs.length != childNodes.size()) {
                throw new Barrier("dynamic_input_layout");
            }

            var validatedInputs = new ArrayList<ValidatedOccurrenceInput>(inputs.length);
            var quantityInputShapes = quantityFeedbackCandidate
                    ? new ArrayList<OmniQuantityFeedbackBatch.InputShape>(inputs.length)
                    : null;
            boolean hasReusableInput = false;
            boolean candidateHasSubstituteInputs = false;
            boolean feedbackOccurrenceTerminal = true;
            int inputIndex = 0;
            for (var entry : childNodes.entrySet()) {
                checkBudget();
                CraftingTreeNode child = entry.getKey();
                var childBridge = (OmniCraftingTreeNodeBridge) child;
                IPatternDetails.IInput input = inputs[inputIndex++];
                if (!sameInputSemantics(
                        childBridge.molecularmanipulator$getParentInput(), input)) {
                    throw new Barrier("dynamic_input_identity");
                }

                AEKey selectedKey = childBridge.molecularmanipulator$getWhat();
                long selectedAmount = childBridge.molecularmanipulator$getAmount();
                if (selectedKey == null || selectedAmount <= 0
                        || !input.isValid(selectedKey, node.level)) {
                    throw new Barrier("dynamic_input_validation");
                }
                GenericStack possibleInput = getPrimaryInputChoice(input);
                boolean fuzzySelectedInput = possibleInput == null
                        || !possibleInput.what().equals(selectedKey)
                        || possibleInput.amount() != selectedAmount;

                long multiplier = input.getMultiplier();
                if (multiplier <= 0 || entry.getValue() == null
                        || entry.getValue() != multiplier) {
                    throw new Barrier("invalid_input_multiplier");
                }

                BoundaryInputMode inputMode = classifyRemainingKey(
                        input, selectedKey, node.level);
                if (inputMode == BoundaryInputMode.UNSAFE) {
                    throw new Barrier("container_items");
                }
                boolean substituteInput = inputMode == BoundaryInputMode.CONSUMABLE
                        && (fuzzySelectedInput || getSingleExactInputChoice(input) == null);
                candidateHasSubstituteInputs |= substituteInput;
                if (inputMode != BoundaryInputMode.CONSUMABLE) {
                    if (!hasContainerItems
                            || childBridge.molecularmanipulator$getAmount() != 1
                            || node.key.equals(childBridge.molecularmanipulator$getWhat())) {
                        throw new Barrier("unsupported_reusable_input");
                    }
                    if (inputMode == BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                        throw new Barrier("recursive_durability_input");
                    }
                    hasReusableInput = true;
                }
                boolean quantityFeedbackInput = quantityFeedbackCandidate
                        && node.key.equals(selectedKey);
                if (quantityFeedbackInput) {
                    feedbackOccurrenceTerminal &=
                            isTerminalQuantityFeedbackOccurrence(childBridge);
                }
                if (quantityInputShapes != null) {
                    quantityInputShapes.add(new OmniQuantityFeedbackBatch.InputShape(
                            quantityFeedbackInput,
                            !fuzzySelectedInput && !substituteInput,
                            inputMode == BoundaryInputMode.CONSUMABLE,
                            inputMode == BoundaryInputMode.CONSUMABLE,
                            selectedAmount, multiplier));
                }
                validatedInputs.add(new ValidatedOccurrenceInput(
                        child, input, inputMode, multiplier, substituteInput,
                        quantityFeedbackInput));
            }
            if (hasContainerItems && !hasReusableInput) {
                throw new Barrier("container_flag_without_supported_input");
            }
            if (!primary && hasReusableInput) {
                throw new Barrier("alternative_reusable_input");
            }

            boolean quantityFeedbackBatch = false;
            if (quantityFeedbackCandidate) {
                var quantityShape = new OmniQuantityFeedbackBatch.Shape(
                        node.amount, node.candidatePatterns.size(),
                        hasContainerItems, patternBarrierReason == null,
                        true, feedbackOccurrenceTerminal, outputEntryCount,
                        outputPerPattern, quantityInputShapes);
                if (!OmniQuantityFeedbackBatch.isSafe(quantityShape)) {
                    throw new Barrier("quantity_limited_pattern");
                }
                quantityFeedbackBatch = true;
                if (!contextSplitKeys.contains(node.key)) {
                    // The feedback child carries AE2's recursion-filtered
                    // occurrence. Split before interning so it can never be
                    // merged into the producing parent as a NodeKey self-edge.
                    throw createContextSplit(
                            node, context, "quantity_feedback_input");
                }
            }

            var patternNodeKey = new NodeKey(node.key, node.amount);
            NodeKey patternOwner = patternOwners.putIfAbsent(details, patternNodeKey);
            if (patternOwner != null && !patternOwner.equals(patternNodeKey)) {
                throw new Barrier("shared_pattern_with_different_request_units");
            }

            var orderedInputs = new ArrayList<OrderedGraphInput>(inputs.length);
            var accumulators = new LinkedHashMap<Integer, EdgeAccumulator>();
            RecipeContext childContext = context.extend(node.key);
            for (ValidatedOccurrenceInput validatedInput : validatedInputs) {
                var childBridge = (OmniCraftingTreeNodeBridge) validatedInput.child;
                if (validatedInput.mode != BoundaryInputMode.CONSUMABLE) {
                    var reusableInput = new GraphReusableInput(
                            validatedInput.input, childBridge,
                            validatedInput.mode, validatedInput.multiplier);
                    node.reusableInputs.add(reusableInput);
                    orderedInputs.add(OrderedGraphInput.reusable(reusableInput));
                    continue;
                }

                int childIndex = intern(validatedInput.child, childContext);
                if (validatedInput.quantityFeedbackInput
                        && childIndex == node.index) {
                    // This must be unreachable after the explicit occurrence
                    // split. Keep a native compatibility boundary if the
                    // graph interning contract ever changes.
                    throw new Barrier("quantity_limited_pattern");
                }
                var consumableInput = new GraphConsumableInput(
                        validatedInput.input, childBridge, childIndex,
                        validatedInput.multiplier, validatedInput.substituteInput);
                orderedInputs.add(OrderedGraphInput.consumable(consumableInput));
                var accumulator = accumulators.get(childIndex);
                if (accumulator == null) {
                    accumulators.put(childIndex,
                            new EdgeAccumulator(validatedInput.multiplier));
                } else {
                    accumulator.requestMultiplier = checkedAdd(
                            accumulator.requestMultiplier, validatedInput.multiplier,
                            "input_multiplier_overflow");
                    accumulator.occurrences++;
                }
            }

            var edges = new ArrayList<Edge>(accumulators.size());
            for (var entry : accumulators.entrySet()) {
                var accumulator = entry.getValue();
                var edge = new Edge(entry.getKey(), accumulator.requestMultiplier,
                        accumulator.occurrences);
                edges.add(edge);
                Node child = nodes.get(entry.getKey());
                if (primary) {
                    node.dependencyEdges.add(edge);
                    child.indegree++;
                }
                child.reachable = true;
            }

            var compiled = new CompiledCandidate(
                    sourceIndex, candidateProcess, details,
                    List.copyOf(orderedInputs),
                    List.copyOf(edges), outputPerPattern,
                    hasContainerItems, limitsQuantity, quantityFeedbackBatch);
            hasSubstituteInputs |= candidateHasSubstituteInputs;
            hasReusableInputs |= hasReusableInput;
            if (primary) {
                node.details = details;
                node.hasContainerItems = hasContainerItems;
                node.limitsQuantity = limitsQuantity;
                node.outputPerPattern = outputPerPattern;
                node.orderedInputs.addAll(orderedInputs);
                node.edges.addAll(edges);
            }
            return compiled;
        }

        /**
         * Resolves the exact AE2 occurrence that will back the feedback edge
         * before any graph state is committed. The producing pattern is
         * recursion-filtered from this child by AE2; accepting only a plain
         * terminal occurrence also excludes emitters and any alternate recipe
         * that could make aggregated feedback depend on execution order.
         */
        private boolean isTerminalQuantityFeedbackOccurrence(
                OmniCraftingTreeNodeBridge child) {
            try {
                if (child.molecularmanipulator$canEmit()) {
                    return false;
                }
                child.molecularmanipulator$buildChildPatterns();
                List<CraftingTreeProcess> processes =
                        child.molecularmanipulator$getProcesses();
                return processes != null && processes.isEmpty();
            } catch (RuntimeException exception) {
                return false;
            }
        }

        /**
         * Verifies that another tree occurrence represented by the same graph
         * node has exactly the same recursion-contextual behavior as the
         * canonical occurrence. Consumable children are interned only after the
         * full occurrence has passed validation, so a rejected context cannot
         * partially mutate the graph.
         */
        private void validateOccurrence(Node node, CraftingTreeNode occurrence,
                RecipeContext context)
                throws Fallback, ContextSplit, InterruptedException {
            var nodeBridge = (OmniCraftingTreeNodeBridge) occurrence;
            if (nodeBridge.molecularmanipulator$getLevel() != node.level) {
                throw new Fallback("contextual_level");
            }

            boolean canEmit = nodeBridge.molecularmanipulator$canEmit();
            if (canEmit != node.emitter) {
                throw new Fallback("contextual_emitter");
            }
            if (canEmit) {
                return;
            }

            nodeBridge.molecularmanipulator$buildChildPatterns();
            List<CraftingTreeProcess> processes = nodeBridge.molecularmanipulator$getProcesses();
            if (processes == null) {
                throw new Fallback("missing_process_state");
            }
            processes = getProgressCandidateProcesses(node, processes, false);
            if (processes.isEmpty()) {
                if (!node.terminal) {
                    logContextualTerminalConflict(node, context, processes);
                    throw createContextSplit(node, context, "contextual_terminal");
                }
                return;
            }
            if (node.terminal) {
                logContextualTerminalConflict(node, context, processes);
                throw createContextSplit(node, context, "contextual_terminal");
            }

            List<IPatternDetails> candidatePatterns = getCandidatePatterns(processes);
            if (candidatePatterns.size() != node.candidatePatterns.size()) {
                throw createContextSplit(
                        node, context, "contextual_pattern_candidates");
            }
            for (int index = 0; index < candidatePatterns.size(); index++) {
                if (!samePatternSemantics(
                        candidatePatterns.get(index),
                        node.candidatePatterns.get(index))) {
                    throw createContextSplit(
                            node, context, "contextual_pattern_candidates");
                }
            }

            for (CompiledCandidate candidate : node.compiledCandidates) {
                if (candidate.sourceIndex >= processes.size()) {
                    throw new Fallback("contextual_candidate_index");
                }
                validateCandidateOccurrence(
                        node, processes.get(candidate.sourceIndex), candidate, context);
            }
        }

        /**
         * Removes exact candidates whose own output component is consumed at
         * least as quickly as it is produced. The process is marked impossible
         * in the live AE2 tree as well, so a later compatibility boundary cannot
         * re-enter the branch that the compiled planner already proved useless.
         */
        private List<CraftingTreeProcess> getProgressCandidateProcesses(
                Node node, List<CraftingTreeProcess> processes,
                boolean canonical) throws Fallback {
            if (processes.isEmpty()) {
                return processes;
            }
            var result = new ArrayList<CraftingTreeProcess>(processes.size());
            for (int candidateIndex = 0;
                    candidateIndex < processes.size(); candidateIndex++) {
                CraftingTreeProcess candidate = processes.get(candidateIndex);
                var bridge = (OmniCraftingTreeProcessBridge) candidate;
                CandidateProgressCheck progress = checkDirectCandidateProgress(
                        node, bridge);
                if (progress.noProgress()) {
                    bridge.molecularmanipulator$setPossible(false);
                    if (canonical) {
                        node.noProgressPatterns.put(
                                bridge.molecularmanipulator$getDetails(),
                                Boolean.TRUE);
                        AppliedEnhancements.LOGGER.info(
                                "Omni MAX_FAST pruned no-progress candidate: key={}, candidate={}, output={}, recursiveDemand={}, pattern={}",
                                node.key, candidateIndex, progress.output(),
                                progress.recursiveDemand(),
                                describePattern(
                                        bridge.molecularmanipulator$getDetails()));
                    }
                    continue;
                }
                result.add(candidate);
            }
            return List.copyOf(result);
        }

        private CandidateProgressCheck checkDirectCandidateProgress(
                Node node, OmniCraftingTreeProcessBridge process) {
            try {
                if (process.molecularmanipulator$hasContainerItems()
                        || process.molecularmanipulator$limitsQuantity()) {
                    return CandidateProgressCheck.PROGRESS;
                }
                IPatternDetails details = process.molecularmanipulator$getDetails();
                if (!isKnownDeterministicPattern(details)) {
                    return CandidateProgressCheck.PROGRESS;
                }

                long output = 0;
                for (GenericStack stack : details.getOutputs()) {
                    if (stack == null || stack.what() == null
                            || stack.amount() <= 0
                            || !node.key.equals(stack.what())) {
                        return CandidateProgressCheck.PROGRESS;
                    }
                    output = Math.addExact(output, stack.amount());
                }
                if (output <= 0) {
                    return CandidateProgressCheck.PROGRESS;
                }

                IPatternDetails.IInput[] inputs = details.getInputs();
                Map<CraftingTreeNode, Long> children =
                        process.molecularmanipulator$getChildNodes();
                if (inputs == null || children == null
                        || inputs.length != children.size()) {
                    return CandidateProgressCheck.PROGRESS;
                }
                long recursiveDemand = 0;
                int inputIndex = 0;
                for (var entry : children.entrySet()) {
                    IPatternDetails.IInput input = inputs[inputIndex++];
                    GenericStack exact = getSingleExactInputChoice(input);
                    var child = (OmniCraftingTreeNodeBridge) entry.getKey();
                    long multiplier = input.getMultiplier();
                    if (exact == null || multiplier <= 0
                            || entry.getValue() == null
                            || entry.getValue() != multiplier
                            || !node.key.equals(exact.what())
                            || !node.key.equals(
                                    child.molecularmanipulator$getWhat())
                            || exact.amount()
                                    != child.molecularmanipulator$getAmount()) {
                        continue;
                    }
                    recursiveDemand = Math.addExact(
                            recursiveDemand,
                            Math.multiplyExact(exact.amount(), multiplier));
                }
                return recursiveDemand > 0 && recursiveDemand >= output
                        ? new CandidateProgressCheck(
                                true, output, recursiveDemand)
                        : CandidateProgressCheck.PROGRESS;
            } catch (RuntimeException exception) {
                return CandidateProgressCheck.PROGRESS;
            }
        }

        private void validateCandidateOccurrence(Node node,
                CraftingTreeProcess candidateProcess, CompiledCandidate candidate,
                RecipeContext context)
                throws Fallback, ContextSplit, InterruptedException {
            var process = (OmniCraftingTreeProcessBridge) candidateProcess;
            if (!samePatternSemantics(
                    process.molecularmanipulator$getDetails(), candidate.details)
                    || process.molecularmanipulator$hasContainerItems()
                            != candidate.hasContainerItems
                    || process.molecularmanipulator$limitsQuantity()
                            != candidate.limitsQuantity) {
                throw new Fallback("contextual_pattern_behavior");
            }

            IPatternDetails.IInput[] inputs = candidate.details.getInputs();
            Map<CraftingTreeNode, Long> childNodes =
                    process.molecularmanipulator$getChildNodes();
            if (inputs == null || childNodes == null
                    || inputs.length != childNodes.size()
                    || inputs.length != candidate.orderedInputs.size()) {
                throw new Fallback("contextual_input_layout");
            }

            var contextualChildren = new ArrayList<ContextualChild>();
            int inputIndex = 0;
            for (var entry : childNodes.entrySet()) {
                checkBudget();
                CraftingTreeNode child = entry.getKey();
                var childBridge = (OmniCraftingTreeNodeBridge) child;
                IPatternDetails.IInput input = inputs[inputIndex];
                OrderedGraphInput expected = candidate.orderedInputs.get(inputIndex++);
                if (!sameInputSemantics(
                        childBridge.molecularmanipulator$getParentInput(), input)) {
                    throw new Fallback("contextual_input_identity");
                }

                GenericStack possibleInput = getPrimaryInputChoice(input);
                AEKey selectedKey = childBridge.molecularmanipulator$getWhat();
                long selectedAmount = childBridge.molecularmanipulator$getAmount();
                if (selectedKey == null || selectedAmount <= 0
                        || !input.isValid(selectedKey, node.level)) {
                    throw new Fallback("contextual_input_template");
                }
                boolean fuzzySelectedInput = possibleInput == null
                        || !possibleInput.what().equals(selectedKey)
                        || possibleInput.amount() != selectedAmount;

                long multiplier = input.getMultiplier();
                if (multiplier <= 0 || entry.getValue() == null
                        || entry.getValue() != multiplier) {
                    throw new Fallback("contextual_input_multiplier");
                }

                BoundaryInputMode inputMode = classifyRemainingKey(
                        input, selectedKey, node.level);
                if (inputMode == BoundaryInputMode.UNSAFE) {
                    throw new Fallback("contextual_input_behavior");
                }
                boolean substituteInput = inputMode == BoundaryInputMode.CONSUMABLE
                        && (fuzzySelectedInput || getSingleExactInputChoice(input) == null);

                if (expected.reusable()) {
                    GraphReusableInput reusable = expected.reusableInput;
                    var canonicalChild = reusable.child;
                    if (inputMode == BoundaryInputMode.CONSUMABLE
                            || !sameInputSemantics(reusable.input, input)
                            || reusable.mode != inputMode
                            || reusable.multiplier != multiplier
                            || !canonicalChild.molecularmanipulator$getWhat().equals(
                                    childBridge.molecularmanipulator$getWhat())
                            || canonicalChild.molecularmanipulator$getAmount()
                                    != childBridge.molecularmanipulator$getAmount()) {
                        throw new Fallback("contextual_reusable_input");
                    }
                } else {
                    GraphConsumableInput consumable = expected.consumableInput;
                    if (inputMode != BoundaryInputMode.CONSUMABLE
                            || consumable == null
                            || !sameInputSemantics(consumable.input, input)
                            || consumable.multiplier != multiplier
                            || consumable.substituteInput != substituteInput) {
                        throw new Fallback("contextual_consumable_input");
                    }
                    Node expectedChild = nodes.get(consumable.childIndex);
                    if (!expectedChild.key.equals(childBridge.molecularmanipulator$getWhat())
                            || expectedChild.amount
                                    != childBridge.molecularmanipulator$getAmount()
                            || !consumable.child.molecularmanipulator$getWhat().equals(
                                    childBridge.molecularmanipulator$getWhat())
                            || consumable.child.molecularmanipulator$getAmount()
                                    != childBridge.molecularmanipulator$getAmount()) {
                        throw new Fallback("contextual_child_template");
                    }
                    contextualChildren.add(new ContextualChild(
                            child, consumable.childIndex));
                }
            }

            RecipeContext childContext = context.extend(node.key);
            for (ContextualChild contextualChild : contextualChildren) {
                int childIndex = intern(contextualChild.child, childContext);
                if (childIndex != contextualChild.expectedIndex) {
                    throw createContextSplit(node, context, "contextual_child_node");
                }
            }
        }

        /**
         * AE2's recursion filter is keyed by the requested item, not by the
         * amount stored in a particular tree node. Nodes for the same key but
         * different request units therefore still need depth-first execution
         * when their visible pattern candidates differ by recursion context.
         *
         * <p>Same-amount occurrences are validated by {@link #validateOccurrence}
         * and, when necessary, recompiled as split nodes. This index only
         * compares different amounts, allowing context-insensitive multi-amount
         * graphs to retain the aggregated topological fast path.</p>
         */
        private void recordKeyContextBehavior(Node node, RecipeContext context,
                boolean emitter, List<IPatternDetails> candidatePatterns) {
            var behavior = new KeyContextBehavior(
                    node.amount, context, emitter, candidatePatterns);
            KeyContextBehavior existing = keyContextBehaviors.putIfAbsent(
                    node.key, behavior);
            if (existing == null || existing.amount == node.amount
                    || sameKeyContextBehavior(existing, behavior)) {
                return;
            }
            if (crossAmountContextSensitiveKeys.add(node.key)
                    && Config.MAX_FAST_DIAGNOSTICS.get()) {
                AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST cross-amount context sensitivity: key={}, canonicalAmount={}, conflictingAmount={}, canonicalPath={}, conflictingPath={}",
                        node.key, existing.amount, node.amount,
                        describeRecipeContext(existing.context),
                        describeRecipeContext(context));
            }
        }

        private boolean sameKeyContextBehavior(KeyContextBehavior left,
                KeyContextBehavior right) {
            if (left.emitter != right.emitter
                    || left.candidatePatterns.size()
                            != right.candidatePatterns.size()) {
                return false;
            }
            for (int index = 0; index < left.candidatePatterns.size(); index++) {
                if (!samePatternSemantics(
                        left.candidatePatterns.get(index),
                        right.candidatePatterns.get(index))) {
                    return false;
                }
            }
            return true;
        }

        private ContextSplit createContextSplit(Node node, RecipeContext context,
                String reason) {
            var keys = new HashSet<AEKey>();
            keys.add(node.key);
            if (!node.occurrenceContexts.isEmpty()) {
                addContextKeys(keys, node.occurrenceContexts.getFirst());
            }
            addContextKeys(keys, context);
            return new ContextSplit(reason, node.key, Set.copyOf(keys));
        }

        private void addContextKeys(Set<AEKey> keys, RecipeContext context) {
            for (RecipeContext cursor = context; cursor.depth > 0; cursor = cursor.parent) {
                keys.add(cursor.key);
            }
        }

        /**
         * Records enough recursion context to identify the exact reversible or
         * cyclic pattern that made a key craftable in one occurrence and a
         * terminal shortage in another. This stays behind the existing
         * diagnostics option because large recipe paths are intentionally
         * omitted from normal logs.
         */
        private void logContextualTerminalConflict(Node node, RecipeContext context,
                List<CraftingTreeProcess> occurrenceProcesses) {
            if (!Config.MAX_FAST_DIAGNOSTICS.get()) {
                return;
            }

            RecipeContext canonicalContext = node.occurrenceContexts.isEmpty()
                    ? RecipeContext.ROOT
                    : node.occurrenceContexts.getFirst();
            RecipeContext terminalContext = node.terminal ? canonicalContext : context;
            var diagnosticPatterns = new ArrayList<IPatternDetails>();
            String patternSource;
            if (node.terminal) {
                patternSource = "conflicting";
                for (CraftingTreeProcess process : occurrenceProcesses) {
                    diagnosticPatterns.add(((OmniCraftingTreeProcessBridge) process)
                            .molecularmanipulator$getDetails());
                }
            } else {
                patternSource = "canonical";
                diagnosticPatterns.addAll(node.candidatePatterns);
            }

            AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST contextual terminal conflict: key={}, amount={}, canonicalTerminal={}, conflictingTerminal={}, canonicalPath={}, conflictingPath={}, patternSource={}, patterns={}",
                    node.key, node.amount, node.terminal, occurrenceProcesses.isEmpty(),
                    describeRecipeContext(canonicalContext), describeRecipeContext(context),
                    patternSource,
                    describeDiagnosticPatterns(diagnosticPatterns, terminalContext));
        }

        private String describeDiagnosticPatterns(List<IPatternDetails> patterns,
                RecipeContext terminalContext) {
            if (patterns.isEmpty()) {
                return "[]";
            }
            var result = new StringBuilder("[");
            int limit = Math.min(patterns.size(), 16);
            for (int index = 0; index < limit; index++) {
                if (index > 0) {
                    result.append(", ");
                }
                IPatternDetails details = patterns.get(index);
                result.append(details == null ? "unknown" : details.getClass().getName())
                        .append(':').append(describePattern(details))
                        .append(" blockedBy=")
                        .append(describePatternBlockers(details, terminalContext));
            }
            if (patterns.size() > limit) {
                result.append(", ... +").append(patterns.size() - limit);
            }
            return result.append(']').toString();
        }

        private String describePatternBlockers(IPatternDetails details,
                RecipeContext context) {
            var result = new StringBuilder("[");
            int blockerCount = 0;
            for (RecipeContext cursor = context;
                    cursor.depth > 0 && blockerCount < 16; cursor = cursor.parent) {
                if (!patternMentions(details, cursor.key)) {
                    continue;
                }
                if (blockerCount++ > 0) {
                    result.append(", ");
                }
                result.append(cursor.key);
            }
            return result.append(']').toString();
        }

        private boolean patternMentions(IPatternDetails details, AEKey ancestor) {
            if (details == null || ancestor == null) {
                return false;
            }
            try {
                for (GenericStack output : details.getOutputs()) {
                    if (output != null && ancestor.matches(output)) {
                        return true;
                    }
                }
                for (IPatternDetails.IInput input : details.getInputs()) {
                    if (input == null) {
                        continue;
                    }
                    GenericStack[] choices = input.getPossibleInputs();
                    if (choices != null && choices.length > 0 && choices[0] != null
                            && ancestor.matches(choices[0])) {
                        return true;
                    }
                }
            } catch (RuntimeException exception) {
                return false;
            }
            return false;
        }

        private String describeRecipeContext(RecipeContext context) {
            var path = new ArrayDeque<AEKey>();
            RecipeContext cursor = context;
            while (cursor.depth > 0 && path.size() < 64) {
                path.addFirst(cursor.key);
                cursor = cursor.parent;
            }
            var result = new StringBuilder("[");
            if (cursor.depth > 0) {
                result.append("... -> ");
            }
            boolean first = true;
            for (AEKey key : path) {
                if (!first) {
                    result.append(" -> ");
                }
                result.append(key);
                first = false;
            }
            return result.append(']').toString();
        }

        private List<IPatternDetails> getCandidatePatterns(
                List<CraftingTreeProcess> processes) throws Fallback {
            var result = new ArrayList<IPatternDetails>(processes.size());
            for (CraftingTreeProcess candidate : processes) {
                var candidateBridge = (OmniCraftingTreeProcessBridge) candidate;
                if (!candidateBridge.molecularmanipulator$isPossible()) {
                    throw new Fallback("contextual_pattern_state");
                }
                result.add(candidateBridge.molecularmanipulator$getDetails());
            }
            return result;
        }

        private int[] buildTopologicalOrder() throws Fallback, InterruptedException {
            var indegrees = new int[nodes.size()];
            var ready = new ArrayDeque<Integer>();
            for (Node node : nodes) {
                indegrees[node.index] = node.indegree;
                if (node.indegree == 0) {
                    ready.addLast(node.index);
                }
            }

            var order = new int[nodes.size()];
            int position = 0;
            while (!ready.isEmpty()) {
                checkBudget();
                int nodeIndex = ready.removeFirst();
                order[position++] = nodeIndex;
                for (Edge edge : nodes.get(nodeIndex).dependencyEdges) {
                    if (--indegrees[edge.childIndex] == 0) {
                        ready.addLast(edge.childIndex);
                    }
                }
            }
            if (position != nodes.size()) {
                throw new Fallback("recursive_or_cyclic_tree");
            }
            return order;
        }

        private long countLogicalNodes(int rootIndex, int[] topologicalOrder)
                throws InterruptedException, Fallback {
            var occurrences = new long[nodes.size()];
            occurrences[rootIndex] = 1;
            long total = 0;
            for (int nodeIndex : topologicalOrder) {
                checkBudget();
                long nodeOccurrences = occurrences[nodeIndex];
                nodes.get(nodeIndex).logicalOccurrences = nodeOccurrences;
                total = saturatedAdd(total, nodeOccurrences);
                for (Edge edge : nodes.get(nodeIndex).edges) {
                    long childOccurrences = saturatedMultiply(nodeOccurrences, edge.occurrences);
                    occurrences[edge.childIndex] = saturatedAdd(
                            occurrences[edge.childIndex], childOccurrences);
                }
            }
            return total;
        }

        private void checkBudget() throws InterruptedException, Fallback {
            long beforePause = System.nanoTime();
            checkpoint(pauseCheckpoint);
            long afterPause = System.nanoTime();
            long paused = Math.max(0, afterPause - beforePause);
            pausedNanos = saturatedAdd(pausedNanos, paused);
            deadline = saturatedAdd(deadline, paused);
            if (afterPause > deadline) {
                throw new Fallback("compile_time_budget");
            }
        }
    }

    private static String getPatternBarrierReason(IPatternDetails details) {
        if (details == null) {
            return "missing_pattern_details";
        }
        if (details.getClass() == AEProcessingPattern.class) {
            return null;
        }
        if (details.getClass() == AECraftingPattern.class) {
            return null;
        }
        if (details.getClass() == AESmithingTablePattern.class) {
            return null;
        }
        if (details.getClass() == AEStonecuttingPattern.class) {
            return null;
        }
        // AdvancedAE's processing pattern has the same deterministic quantity
        // semantics as AEProcessingPattern. Keep this an exact-name opt-in so
        // AdvancedAE remains optional and unknown implementations/subclasses
        // still go through the native AE2 compatibility path. The compiler's
        // input, output, remainder and runtime-template checks remain mandatory.
        if (ADVANCED_AE_PROCESSING_PATTERN.equals(details.getClass().getName())) {
            return null;
        }
        // AE2 Lab Tech's overload pattern is deterministic and follows standard
        // input/output semantics. Support it to enable MAX_FAST for creative-tier
        // recipes that use overload patterns.
        if (AE2LT_OVERLOAD_PATTERN.equals(details.getClass().getName())) {
            return null;
        }

        // The single policy tries any pattern type and relies on runtime checks.
        // The compiler still validates inputs, outputs, remainders, and multipliers.
        // This enables future pattern types without code changes, at the cost of
        // potentially wasting compilation time on incompatible patterns.
        return "unknown_pattern_type:" + details.getClass().getName();
    }

    private static boolean requiresImmediateFallback(String barrierReason) {
        return "missing_pattern_details".equals(barrierReason)
                || "missing_primary_output".equals(barrierReason)
                || barrierReason.startsWith("unsupported_pattern_type:");
    }

    private static boolean samePatternSemantics(
            IPatternDetails left, IPatternDetails right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getClass() != right.getClass()) {
            return false;
        }
        try {
            if (!java.util.Objects.equals(
                    left.getDefinition(), right.getDefinition())
                    || !sameStacks(left.getOutputs(), right.getOutputs())) {
                return false;
            }
            IPatternDetails.IInput[] leftInputs = left.getInputs();
            IPatternDetails.IInput[] rightInputs = right.getInputs();
            if (leftInputs == null || rightInputs == null
                    || leftInputs.length != rightInputs.length) {
                return false;
            }
            for (int index = 0; index < leftInputs.length; index++) {
                if (!sameInputSemantics(leftInputs[index], rightInputs[index])) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean sameInputSemantics(
            IPatternDetails.IInput left, IPatternDetails.IInput right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getClass() != right.getClass()) {
            return false;
        }
        try {
            return left.getMultiplier() == right.getMultiplier()
                    && sameStacks(
                            left.getPossibleInputs(), right.getPossibleInputs());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean sameStacks(
            GenericStack[] left, GenericStack[] right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        for (int index = 0; index < left.length; index++) {
            if (!java.util.Objects.equals(left[index], right[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameStacks(
            List<GenericStack> left, List<GenericStack> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!java.util.Objects.equals(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static GenericStack getPrimaryInputChoice(IPatternDetails.IInput input) {
        GenericStack[] choices = input.getPossibleInputs();
        if (choices == null || choices.length == 0) {
            return null;
        }
        GenericStack first = choices[0];
        return first == null || first.what() == null || first.amount() <= 0
                ? null
                : first;
    }

    private static GenericStack getSingleExactInputChoice(IPatternDetails.IInput input) {
        GenericStack first = getPrimaryInputChoice(input);
        if (first == null) {
            return null;
        }
        GenericStack[] choices = input.getPossibleInputs();
        for (int index = 1; index < choices.length; index++) {
            GenericStack choice = choices[index];
            if (choice == null || choice.what() == null || choice.amount() <= 0
                    || choice.amount() != first.amount()
                    || !choice.what().equals(first.what())) {
                return null;
            }
        }
        return first;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    private static void checkpoint(PauseCheckpoint pauseCheckpoint)
            throws InterruptedException {
        checkInterrupted();
        pauseCheckpoint.pause();
    }

    private static long saturatedAdd(long left, long right) {
        if (left <= 0) {
            return Math.max(0, right);
        }
        if (right <= 0) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long checkedAdd(long left, long right, String reason) throws Fallback {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
            throw new Fallback(reason);
        }
        return left + right;
    }

    private static long checkedMultiply(long left, long right, String reason) throws Fallback {
        if (left < 0 || right < 0 || (right != 0 && left > Long.MAX_VALUE / right)) {
            throw new Fallback(reason);
        }
        return left * right;
    }

    private static long ceilDiv(long value, long divisor) throws Fallback {
        if (divisor == 0) {
            throw new Fallback("division_by_zero_output_per_pattern");
        }
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private record NodeKey(AEKey key, long amount) {
    }

    private record ValidatedOccurrenceInput(CraftingTreeNode child,
            IPatternDetails.IInput input, BoundaryInputMode mode, long multiplier,
            boolean substituteInput, boolean quantityFeedbackInput) {
    }

    private record ContextualChild(CraftingTreeNode child, int expectedIndex) {
    }

    private enum CandidateAttemptStatus {
        APPLIED,
        SHORTAGE,
        FALLBACK
    }

    private record CandidateAttempt(CandidateAttemptStatus status,
            ChildCraftingSimulationState inventory, KeyCounter missing,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleStates,
            String reason) {
    }

    private record KeyContextBehavior(long amount, RecipeContext context,
            boolean emitter, List<IPatternDetails> candidatePatterns) {
    }

    /**
     * Ordered ancestor-key chain used by AE2's recursion filter. Keeping it
     * persistent makes sibling occurrences cheap, while structural equality
     * safely deduplicates equivalent paths without relying on a hash alone.
     */
    private static final class RecipeContext {
        private static final RecipeContext ROOT = new RecipeContext();

        private final RecipeContext parent;
        private final AEKey key;
        private final int depth;
        private final int hash;

        private RecipeContext() {
            this.parent = null;
            this.key = null;
            this.depth = 0;
            this.hash = 1;
        }

        private RecipeContext(RecipeContext parent, AEKey key) {
            this.parent = parent;
            this.key = key;
            this.depth = parent.depth + 1;
            this.hash = 31 * parent.hash + key.hashCode();
        }

        private RecipeContext extend(AEKey key) {
            return new RecipeContext(this, key);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof RecipeContext other)
                    || depth != other.depth || hash != other.hash) {
                return false;
            }
            RecipeContext left = this;
            RecipeContext right = other;
            while (left.depth > 0) {
                if (!left.key.equals(right.key)) {
                    return false;
                }
                left = left.parent;
                right = right.parent;
            }
            return true;
        }
    }

    /**
     * Recursion context alone is insufficient because two slots may request
     * the same key and amount but use different substitution or remainder
     * rules. Parent inputs therefore participate by identity, matching AE2's
     * own tree-node construction.
     */
    private static final class OccurrenceContext {
        private final RecipeContext recipeContext;
        private final IPatternDetails.IInput parentInput;
        private final int hash;

        private OccurrenceContext(RecipeContext recipeContext,
                IPatternDetails.IInput parentInput) {
            this.recipeContext = recipeContext;
            this.parentInput = parentInput;
            this.hash = 31 * recipeContext.hashCode()
                    + System.identityHashCode(parentInput);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object object) {
            return this == object
                    || object instanceof OccurrenceContext other
                            && parentInput == other.parentInput
                            && recipeContext.equals(other.recipeContext);
        }
    }

    private static final class Node {
        private final int index;
        private final AEKey key;
        private final long amount;
        private final net.minecraft.world.level.Level level;
        private String orderedFallbackReason;
        private String orderedFallbackDetail;
        private final List<CraftingTreeNode> occurrences = new ArrayList<>();
        private final List<RecipeContext> occurrenceContexts = new ArrayList<>();
        private final IdentityHashMap<CraftingTreeNode, Boolean> occurrenceSet =
                new IdentityHashMap<>();
        private final Map<OccurrenceContext, CraftingTreeNode> contextOccurrences =
                new HashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private final List<Edge> dependencyEdges = new ArrayList<>();
        private final List<GraphReusableInput> reusableInputs = new ArrayList<>();
        private final List<OrderedGraphInput> orderedInputs = new ArrayList<>();
        private final List<CompiledCandidate> compiledCandidates = new ArrayList<>();
        private final IdentityHashMap<IPatternDetails, Boolean>
                noProgressPatterns = new IdentityHashMap<>();
        private final Map<Integer, String> candidateCompileFailures =
                new LinkedHashMap<>();
        private int indegree;
        private int inspectedOccurrences;
        private boolean inspectionQueued;
        private boolean emitter;
        private boolean terminal;
        private boolean reachable;
        private boolean barrier;
        private String barrierReason;
        private IPatternDetails details;
        private List<IPatternDetails> candidatePatterns = List.of();
        private boolean hasContainerItems;
        private boolean limitsQuantity;
        private long outputPerPattern;
        private long logicalOccurrences;
        private boolean allCandidatesCompiled;
        private Boolean deterministicCandidateSubgraph;
        private Boolean batchValidFirstCandidateSubgraph;
        private Boolean directStockFirstCandidate;
        private Boolean directStockCandidateSet;
        private ExecutionMode executionMode = ExecutionMode.PURE_FAST;

        private Node(int index, AEKey key, long amount, net.minecraft.world.level.Level level) {
            this.index = index;
            this.key = key;
            this.amount = amount;
            this.level = level;
        }
    }

    private enum ExecutionMode {
        PURE_FAST,        // Full batch execution
        HYBRID_BARRIER,   // Upstream batch + this node calls AE2
        FULL_FALLBACK     // Entire subtree uses AE2
    }

    private record Edge(int childIndex, long requestMultiplier, int occurrences) {
    }

    private record CompiledCandidate(int sourceIndex,
            CraftingTreeProcess sourceProcess, IPatternDetails details,
            List<OrderedGraphInput> orderedInputs, List<Edge> edges,
            long outputPerPattern, boolean hasContainerItems,
            boolean limitsQuantity, boolean quantityFeedbackBatch) {
    }

    private record CandidateProgressCheck(
            boolean noProgress, long output, long recursiveDemand) {
        private static final CandidateProgressCheck PROGRESS =
                new CandidateProgressCheck(false, 0, 0);
    }

    private static final class RuntimeQuantityStockGuard {
        private long patternTimes;
        private int stockedInputKeys;
    }

    private static final class EdgeAccumulator {
        private long requestMultiplier;
        private int occurrences = 1;

        private EdgeAccumulator(long requestMultiplier) {
            this.requestMultiplier = requestMultiplier;
        }
    }

    private record Graph(List<Node> nodes, int[] topologicalOrder, int rootIndex,
            long logicalNodeCount, long mergedOccurrences, int barrierCount,
            int orderedChoiceCount, boolean contextSensitive,
            boolean hasSubstituteInputs, boolean hasReusableInputs) {
        private String executionSafetyFailure() {
            return null;
        }

        private boolean requiresNativeNodeCount() {
            return executionScope()
                    != OmniMaxFastExecutionPolicy.Scope.PURE_TOPOLOGICAL;
        }

        private boolean hasLocalBoundaries() {
            for (Node node : nodes) {
                if (node.reachable && (node.barrier
                        || node.executionMode != ExecutionMode.PURE_FAST)) {
                    return true;
                }
            }
            return false;
        }

        private OmniMaxFastExecutionPolicy.Scope executionScope() {
            return OmniMaxFastExecutionPolicy.select(
                    contextSensitive, hasLocalBoundaries(),
                    hasSubstituteInputs, hasReusableInputs);
        }

        private boolean hasOrderedChoices() {
            return orderedChoiceCount > 0;
        }
    }

    private static final class Barrier extends Exception {
        private final String reason;

        private Barrier(String reason) {
            this.reason = reason;
        }
    }

    private static final class ContextSplit extends Exception {
        private final String reason;
        private final AEKey triggerKey;
        private final Set<AEKey> keys;

        private ContextSplit(String reason, AEKey triggerKey, Set<AEKey> keys) {
            this.reason = reason;
            this.triggerKey = triggerKey;
            this.keys = keys;
        }
    }

    private static final class Fallback extends Exception {
        private final String reason;

        private Fallback(String reason) {
            this.reason = reason;
        }
    }

    private static final class CertifiedPrefixProbeFallback
            extends RuntimeException {
        private final String reason;

        private CertifiedPrefixProbeFallback(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}
