package com.github.appliedenhancements.crafting.aelis;

import com.appliedenhancements.runtime.ExactCraftingBytes;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import com.appliedenhancements.Config;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.appliedenhancements.mixin.AelisChildSimulationStateAccessor;
import com.appliedenhancements.mixin.CraftingSimulationStateLongSafetyAccessor;
import com.github.appliedenhancements.crafting.MolecularReusableInputAdapters;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftingTracker;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeNodeBridge;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeProcessBridge;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftingTracker;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class AelisPlanner {
    private static final long MAX_LINEAR_NATIVE_BOUNDARY_ITEMS = 8_192L;
    private static final long MAX_SIMULATION_ORDERED_REPLAY_STEPS = 64L;
    private static final String ADVANCED_AE_PROCESSING_PATTERN =
            "net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern";
    private static final String AE2LT_OVERLOAD_PATTERN =
            "com.moakiee.ae2lt.overload.pattern.Ae2OverloadPatternDetails";
    private AelisPlanner() {
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
        private final ICraftingService craftingService;
        private CraftingTreeNode root;
        private Graph graph;
        private String structuralFailure;
        private String transactionalRuntimeFailure;
        private Throwable structuralError;
        private long compileNanos;

        public Session(int maxNodes, int compileBudgetMillis,
                PauseCheckpoint pauseCheckpoint) {
            this(maxNodes, compileBudgetMillis, pauseCheckpoint,
                    ProgressSink.NONE, null);
        }

        public Session(int maxNodes, int compileBudgetMillis,
                PauseCheckpoint pauseCheckpoint, ProgressSink progressSink) {
            this(maxNodes, compileBudgetMillis, pauseCheckpoint,
                    progressSink, null);
        }

        public Session(int maxNodes, int compileBudgetMillis,
                PauseCheckpoint pauseCheckpoint, ProgressSink progressSink,
                ICraftingService craftingService) {
            this.maxNodes = maxNodes;
            this.compileBudgetNanos = TimeUnit.MILLISECONDS.toNanos(compileBudgetMillis);
            this.pauseCheckpoint = pauseCheckpoint;
            this.progressSink = progressSink == null ? ProgressSink.NONE : progressSink;
            this.craftingService = craftingService;
        }

        public Result tryExecute(CraftingTreeNode requestedRoot, CraftingSimulationState inventory,
                long requestedAmount, boolean simulation, KeyCounter missingItems)
                throws InterruptedException {
            try (var seeds = new AelisIgnoredSeedScope(inventory)) {
                var result = tryExecuteWithSeeds(requestedRoot, inventory, requestedAmount, simulation, missingItems);
                if (result.applied()) seeds.commit();
                return result;
            }
        }

        private Result tryExecuteWithSeeds(CraftingTreeNode requestedRoot, CraftingSimulationState inventory,
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
                long startedAt = System.nanoTime();
                long compileDeadline = saturatedAdd(startedAt, compileBudgetNanos);
                long pausedNanos = 0;
                var contextSplitKeys = new HashSet<AEKey>();
                try {
                    progressSink.compilationStarted();
                    while (graph == null && structuralFailure == null) {
                        var compiler = new Compiler(maxNodes, compileDeadline,
                                pauseCheckpoint, Set.copyOf(contextSplitKeys),
                                progressSink, craftingService);
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
                            }
                        } catch (Fallback fallback) {
                            structuralFailure = fallback.reason;
                        } catch (RuntimeException | LinkageError exception) {
                            structuralFailure = "internal_compile_exception";
                            structuralError = exception;
                        } finally {
                            compileDeadline = compiler.deadline;
                            pausedNanos = saturatedAdd(pausedNanos, compiler.pausedNanos);
                        }
                    }
                } catch (RuntimeException | LinkageError exception) {
                    structuralFailure = "internal_compile_exception";
                    structuralError = exception;
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
                        == AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL;
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
                        == AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL) {
                    transactionalRuntimeFailure = "transactional_graph_" + fallback.reason;
                }
                return Result.fallback(fallback.reason, graph.nodes.size(), graph.mergedOccurrences,
                        graph.barrierCount,
                        compileNanos, System.nanoTime() - startedAt, null);
            } catch (RuntimeException | LinkageError exception) {
                if (graph.executionScope()
                        == AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL) {
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
        var cyclicMissing = new KeyCounter();
        if (tryExecuteGlobalCyclicPlan(
                graph, inventory, requestedAmount, simulation,
                cyclicMissing, pauseCheckpoint)) {
            inventory.applyDiff(parent);
            mergeMissingAmounts(missingItems, cyclicMissing);
            return;
        }
        var exactRequest = com.appliedenhancements.runtime.ExactRequestScope.current();
        if (exactRequest != null && exactRequest.amount().bitLength() > 63) {
            var stagedMissing = new KeyCounter();
            if (graph.executionScope() == AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL) {
                executeTransactionalNode(graph, graph.rootIndex, inventory, exactRequest.amount(),
                        requestedAmount, simulation, stagedMissing, pauseCheckpoint, progressSink,
                        -1, null, null, false);
            } else {
                executeBigIntegerTopological(graph, inventory, requestedAmount, simulation, stagedMissing,
                        pauseCheckpoint, progressSink);
            }
            inventory.applyDiff(parent);
            mergeMissingAmounts(missingItems, stagedMissing);
            return;
        }
        if (graph.executionScope()
                == AelisExecutionPolicy.Scope.CONTEXTUAL_TRANSACTIONAL) {
            var stagedMissing = new KeyCounter();
            executeTransactionalNode(
                    graph, graph.rootIndex, inventory, requestedAmount,
                    requestedAmount, simulation, stagedMissing,
                    pauseCheckpoint, progressSink);
            inventory.applyDiff(parent);
            mergeMissingAmounts(missingItems, stagedMissing);
            return;
        }
        if ((Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || com.appliedenhancements.runtime.ExactRequestScope.current() != null)) {
            var stagedMissing = new KeyCounter();
            executeBigIntegerTopological(
                    graph, inventory, requestedAmount, simulation, stagedMissing,
                    pauseCheckpoint, progressSink);
            inventory.applyDiff(parent);
            mergeMissingAmounts(missingItems, stagedMissing);
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
                            requestedAmount, simulation, stagedMissing, pauseCheckpoint, null)) {
                        progressSink.executionStep();
                        continue;
                    }
                }
                if (isReusableBoundaryReason(node.barrierReason)
                        && tryExecuteReusableContainerBoundary(
                        node, inventory, requestMultipliers, pauseCheckpoint)) {
                    progressSink.executionStep();
                    continue;
                }
                // Call AE2 bridge with aggregated amount from upstream
                executeNativeBoundary(node, inventory, requestMultipliers);

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
                executeNativeBoundary(node, inventory, requestMultipliers);
                progressSink.executionStep();
                continue;
            }
            validateObservedPatternSemantics(node, null);
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
                com.appliedenhancements.runtime.AelisPlanningLog.warning(
                        "invalid_output_per_pattern",
                        "AELIS rejected invalid outputPerPattern: key={}, amount={}, barrier={}, executionMode={}, logicalOccurrences={}",
                        node.key, node.amount, node.barrier,
                        node.executionMode, node.logicalOccurrences);
                throw new Fallback("invalid_output_per_pattern");
            }
            long patternTimes = ceilDiv(totalRequestedItems, effectiveOutputPerPattern);
            allocateDeterministicDamageInputs(
                    node, inventory, patternTimes, pauseCheckpoint);
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
            ExactCraftingBytes.addBytes(inventory, patternTimes);
            progressSink.executionStep();
        }

        inventory.applyDiff(parent);
        mergeMissingAmounts(missingItems, stagedMissing);
    }

    private static void executeBigIntegerTopological(
            Graph graph, CraftingSimulationState inventory,
            long requestedAmount, boolean simulation, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, ProgressSink progressSink)
            throws Fallback, CraftBranchFailure, InterruptedException {
        var requests = new AelisQuantity[graph.nodes.size()];
        java.util.Arrays.fill(requests, AelisQuantity.ZERO);
        var exactRequest = com.appliedenhancements.runtime.ExactRequestScope.current();
        requests[graph.rootIndex] = exactRequest == null
                ? AelisQuantity.of(requestedAmount) : AelisQuantity.of(exactRequest.amount());

        for (int nodeIndex : graph.topologicalOrder) {
            checkpoint(pauseCheckpoint);
            AelisQuantity requestMultipliers = requests[nodeIndex];
            if (requestMultipliers.signum() <= 0) {
                progressSink.executionStep();
                continue;
            }

            Node node = graph.nodes.get(nodeIndex);
            if (node.executionMode == ExecutionMode.HYBRID_BARRIER || node.barrier) {
                long nativeRequest = exactNonNegativeLong(
                        requestMultipliers.toBigInteger(), "big_integer_native_boundary_overflow");
                if (node.executionMode == ExecutionMode.HYBRID_BARRIER
                        && usesCompiledCandidateTrial(node)
                        && tryExecuteCompiledCandidates(
                                graph, nodeIndex, inventory, nativeRequest,
                                requestedAmount, simulation, stagedMissing,
                                pauseCheckpoint, null)) {
                    progressSink.executionStep();
                    continue;
                }
                if (isReusableBoundaryReason(node.barrierReason)
                        && tryExecuteReusableContainerBoundary(
                                node, inventory, nativeRequest, pauseCheckpoint)) {
                    progressSink.executionStep();
                    continue;
                }
                executeNativeBoundary(node, inventory, nativeRequest);
                progressSink.executionStep();
                continue;
            }

            validateObservedPatternSemantics(node, null);
            validateTemplates(node, inventory, pauseCheckpoint);
            AelisQuantity requestedItems = requestMultipliers.multiply(node.amount);
            BigInteger exactRequestedItems = requestedItems.toBigInteger();
            ExactCraftingBytes.addStackBytes(inventory, node.key, exactRequestedItems);

            if (com.appliedenhancements.storage.InfiniteStorageSupport.consume(
                    inventory, node.key, exactRequestedItems)) {
                progressSink.executionStep();
                continue;
            }

            long available = inventory.extract(
                    node.key, requestedItems.saturatingLong(),
                    Actionable.SIMULATE);
            long extractedMultipliers = available / node.amount;
            if (extractedMultipliers > 0) {
                long extractedAmount = node.amount * extractedMultipliers;
                long extracted = inventory.extract(
                        node.key, extractedAmount, Actionable.MODULATE);
                if (extracted != extractedAmount) {
                    throw new IllegalStateException(
                            "Crafting simulation inventory changed during exact extraction");
                }
            }

            AelisQuantity remainingMultipliers = requestMultipliers.subtract(extractedMultipliers);
            if (remainingMultipliers.signum() == 0) {
                progressSink.executionStep();
                continue;
            }

            AelisQuantity totalRequestedItems = remainingMultipliers.multiply(node.amount);
            if (node.emitter) {
                inventory.emitItems(
                        node.key,
                        exactNonNegativeLong(
                                totalRequestedItems.toBigInteger(),
                                "big_integer_emitted_amount_overflow"));
                progressSink.executionStep();
                continue;
            }
            if (node.terminal) {
                long projectedMissing = totalRequestedItems.saturatingLong();
                if (!simulation) {
                    throw new CraftBranchFailure(node.key, projectedMissing);
                }
                addSaturated(stagedMissing, node.key, projectedMissing);
                recordBigIntegerMissing(inventory, node.key, totalRequestedItems.toBigInteger());
                progressSink.executionStep();
                continue;
            }

            long effectiveOutputPerPattern = node.outputPerPattern;
            if (effectiveOutputPerPattern <= 0) {
                throw new Fallback("invalid_output_per_pattern");
            }
            AelisQuantity exactPatternTimes;
            try {
                exactPatternTimes = totalRequestedItems.ceilDiv(effectiveOutputPerPattern);
            } catch (ArithmeticException exception) {
                throw new Fallback("division_by_zero_output_per_pattern");
            }
            BigInteger exactTimes = exactPatternTimes.toBigInteger();
            long patternTimes = projectedPatternTimes(inventory, node.details, exactTimes);
            if (!node.reusableInputs.isEmpty()) {
                allocateDeterministicDamageInputs(
                        node, inventory, exactNonNegativeLong(exactTimes,
                                "big_integer_damage_input_overflow"), pauseCheckpoint);
            }
            for (Edge edge : node.edges) {
                AelisQuantity childRequests = exactPatternTimes.multiply(edge.requestMultiplier);
                requests[edge.childIndex] = requests[edge.childIndex].add(childRequests);
            }

            AelisQuantity surplus = exactPatternTimes.multiply(effectiveOutputPerPattern)
                    .subtract(totalRequestedItems);
            if (surplus.signum() > 0) {
                inventory.insert(
                        node.key,
                        exactNonNegativeLong(
                                surplus.toBigInteger(), "big_integer_surplus_overflow"),
                        Actionable.MODULATE);
            }
            recordProjectedBigIntegerCrafting(
                    inventory, node.details, exactTimes, patternTimes);
            ExactCraftingBytes.addBytes(inventory, exactTimes);
            progressSink.executionStep();
        }
    }

    private static long projectedPatternTimes(
            CraftingSimulationState inventory, IPatternDetails pattern,
            BigInteger exactPatternTimes) {
        long current = ((CraftingSimulationStateLongSafetyAccessor) inventory)
                .appliedenhancements$getCrafts()
                .getOrDefault(pattern, 0L);
        return AelisBigIntegerMath.projectedAddition(exactPatternTimes, current);
    }

    private static void recordBigIntegerMissing(CraftingSimulationState inventory,
            AEKey key, BigInteger amount) {
        var tracker = (AelisBigIntegerCraftingTracker) inventory;
        tracker.appliedenhancements$recordBigIntegerMissing(key, amount);
    }

    private static void recordProjectedBigIntegerCrafting(
            CraftingSimulationState inventory, IPatternDetails pattern,
            BigInteger exactPatternTimes, long projectedPatternTimes) {
        var tracker = (AelisBigIntegerCraftingTracker) inventory;
        if (!exactPatternTimes.equals(BigInteger.valueOf(projectedPatternTimes))) {
            tracker.appliedenhancements$setPreviewOnly(true);
        }
        ((AelisCalculationPathCarrier) inventory).molecularmanipulator$setCalculationPath(AelisCalculationPath.AELIS);
        tracker.appliedenhancements$recordBigIntegerCrafting(
                pattern, exactPatternTimes);
        if (projectedPatternTimes <= 0) {
            return;
        }
        tracker.appliedenhancements$beginProjectedCraftingTransfer();
        try {
            inventory.addCrafting(pattern, projectedPatternTimes);
        } finally {
            tracker.appliedenhancements$endProjectedCraftingTransfer();
        }
    }

    /**
     * Tries a whole-graph integer plan when the key-level dependency graph is
     * cyclic and every reachable producer exposes a complete exact candidate
     * set. The solver sees all shared demands before any inventory mutation,
     * so one startup seed can serve every occurrence of the same cycle.
     */
    /** Builds the validated model shared by real and simulated global attempts. */
    private static Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>>
            buildGlobalCyclicVariants(Graph graph, CraftingSimulationState parent,
                    PauseCheckpoint pauseCheckpoint) throws InterruptedException, Fallback {
        prepareGlobalCyclicModel(graph);
        if (graph.cyclicVariants == null) {
            return null;
        }
        for (Node node : graph.nodes) {
            if (!node.reachable) continue;
            for (CompiledCandidate candidate : node.compiledCandidates) {
                if (candidate.observedPatternSemantics != null
                        && !candidate.observedPatternSemantics.matches(candidate.details)) {
                    return null;
                }
                for (OrderedGraphInput input : candidate.orderedInputs) {
                    GraphConsumableInput consumable = input.consumableInput;
                    if (!validateSparseConsumableTemplates(
                            graph.nodes.get(consumable.childIndex), consumable.child,
                            parent, pauseCheckpoint)) return null;
                }
            }
        }
        for (LocalCyclicRegion local : graph.cyclicRegions) {
            for (var variants : local.model.variants().values()) {
                for (var variant : variants) {
                    CompiledCandidate candidate = variant.id();
                    if (candidate.sourceProcess != null) continue;
                    if (!rawCyclePatternStillAvailable(graph.craftingService, candidate.details)
                            || candidate.observedPatternSemantics != null
                                    && !candidate.observedPatternSemantics.matches(candidate.details)) {
                        return null;
                    }
                    for (RawCycleInput rawInput : candidate.rawCycleInputs) {
                        if (!validateRawCycleInput(rawInput)) return null;
                    }
                    for (OrderedGraphInput input : candidate.orderedInputs) {
                        GraphConsumableInput consumable = input.consumableInput;
                        if (!validateSparseConsumableTemplates(
                                graph.nodes.get(consumable.childIndex), consumable.child,
                                parent, pauseCheckpoint)) return null;
                    }
                }
            }
        }
        return graph.cyclicVariants;
    }

    private static void prepareGlobalCyclicModel(Graph graph) throws Fallback {
        if (graph.cyclicModelPrepared) return;
        var variants = compileGlobalCyclicVariants(graph);
        graph.cyclicVariants = variants != null && containsKeyLevelCycle(variants)
                ? java.util.Collections.unmodifiableMap(variants) : null;
        graph.cyclicModelPrepared = true;
    }

    /** Compile immutable quantities once; runtime validation stays in the method above. */
    private static Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>>
            compileGlobalCyclicVariants(Graph graph) throws Fallback {
        var variants = new LinkedHashMap<AEKey,
                List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>>();
        var canonicalCandidates = new LinkedHashMap<AEKey, List<CompiledCandidate>>();

        for (Node node : graph.nodes) {
            if (!node.reachable) {
                continue;
            }
            if (node.compiledCandidates.isEmpty()) {
                continue;
            }
            if (node.emitter || node.barrier
                    || node.executionMode != ExecutionMode.PURE_FAST
                    || !node.allCandidatesCompiled
                    || node.compiledCandidates.size()
                            != node.candidatePatterns.size()) {
                return null;
            }
            List<CompiledCandidate> existing = canonicalCandidates.get(node.key);
            if (existing != null) {
                if (!sameCycleCandidateSet(
                        graph.nodes, existing, node.compiledCandidates)) {
                    return null;
                }
                continue;
            }

            var keyVariants = new ArrayList<
                    AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>(
                            node.compiledCandidates.size());
            for (CompiledCandidate candidate : node.compiledCandidates) {
                if (candidate == null || !candidate.secondaryOutputs.isEmpty()
                        || candidate.outputPerPattern <= 0
                        || candidate.hasContainerItems) {
                    return null;
                }
                var inputs = new LinkedHashMap<AEKey, BigInteger>();
                for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
                    if (orderedInput.reusable()) {
                        return null;
                    }
                    GraphConsumableInput consumable = orderedInput.consumableInput;
                    if (consumable == null || consumable.substituteInput
                            || consumable.childIndex < 0
                            || consumable.childIndex >= graph.nodes.size()) {
                        return null;
                    }
                    Node child = graph.nodes.get(consumable.childIndex);
                    long amount = checkedMultiply(
                            child.amount, consumable.multiplier,
                            "cyclic_input_amount_overflow");
                    inputs.merge(
                            child.key, BigInteger.valueOf(amount),
                            BigInteger::add);
                }
                var modelInputs = new ArrayList<
                        AelisCyclicDemandSolver.Input<AEKey>>(inputs.size());
                for (var input : inputs.entrySet()) {
                    modelInputs.add(new AelisCyclicDemandSolver.Input<>(
                            input.getKey(), input.getValue()));
                }
                keyVariants.add(new AelisCyclicDemandSolver.Variant<>(
                        candidate,
                        node.key,
                        BigInteger.valueOf(candidate.outputPerPattern),
                        modelInputs));
            }
            canonicalCandidates.put(node.key, List.copyOf(node.compiledCandidates));
            variants.put(node.key, List.copyOf(keyVariants));
        }

        var rawRegions = new IdentityHashMap<LocalCyclicRegion, Boolean>();
        for (LocalCyclicRegion local : graph.cyclicRegions) {
            if (local != null) {
                rawRegions.put(local, Boolean.TRUE);
            }
        }
        if (!rawRegions.isEmpty()) {
            var regionModels = new ArrayList<
                    AelisCyclicRegionDetector.Region<AEKey, CompiledCandidate>>(
                            rawRegions.size());
            for (LocalCyclicRegion local : rawRegions.keySet()) {
                for (var regionEntry : local.model.variants().entrySet()) {
                    for (var variant : regionEntry.getValue()) {
                        CompiledCandidate candidate = variant.id();
                        if (candidate.sourceProcess != null) {
                            continue;
                        }
                        for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
                            GraphConsumableInput consumable =
                                    orderedInput.consumableInput;
                            if (orderedInput.reusable() || consumable == null
                                    || consumable.substituteInput
                                    || consumable.childIndex < 0
                                    || consumable.childIndex >= graph.nodes.size()) {
                                return null;
                            }
                        }
                    }
                }
                regionModels.add(local.model);
            }
            var merged = AelisCyclicPatternOverlay.mergeIntoGlobal(
                    variants, regionModels);
            variants.clear();
            variants.putAll(merged);
        }

        return variants;
    }

    private static boolean tryExecuteGlobalCyclicPlan(
            Graph graph, CraftingSimulationState parent,
            long requestedAmount, boolean simulation,
            KeyCounter stagedMissing, PauseCheckpoint pauseCheckpoint)
            throws InterruptedException, Fallback {
        try (var seedLease = AelisIgnoredSeedScope.lease(parent)) {
        var variants = buildGlobalCyclicVariants(graph, parent, pauseCheckpoint);
        if (variants == null) {
            return false;
        }
        var modelKeys = new LinkedHashMap<AEKey, Boolean>();
        for (Node node : graph.nodes) {
            if (node.reachable) modelKeys.put(node.key, Boolean.TRUE);
        }
        for (var entry : variants.entrySet()) {
            modelKeys.put(entry.getKey(), Boolean.TRUE);
            for (var variant : entry.getValue()) {
                for (var input : variant.inputs()) modelKeys.put(input.key(), Boolean.TRUE);
            }
        }

        Set<CompiledCandidate> cyclicCraftingCandidates =
                AelisCyclicCraftingMembership.find(variants);

        Node root = graph.nodes.get(graph.rootIndex);
        var exactRequest = com.appliedenhancements.runtime.ExactRequestScope.current();
        BigInteger rootItems = cyclicRequestedItems(root.amount, requestedAmount);
        var available = new LinkedHashMap<AEKey, BigInteger>();
        for (AEKey key : modelKeys.keySet()) {
            long amount = parent.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
            if (amount > 0) {
                available.put(key, BigInteger.valueOf(amount));
            }
        }

        long startedAt = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(
                Config.CYCLE_SOLVER_BUDGET_MS.get());
        long deadline = saturatedAdd(startedAt, budgetNanos);
        var limits = new AelisCyclicDemandSolver.Limits(
                Config.CYCLE_SOLVER_MAX_SCC_NODES.get(),
                Config.CYCLE_SOLVER_MAX_SEARCH_STATES.get(),
                deadline, Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || exactRequest != null);
        PreservedGlobalCyclePlan preserved = solveGlobalCyclePlan(
                variants, root.key, rootItems,
                available, limits);
        var result = preserved.result();
        if (result != null && result.solved() && value(available, root.key).signum() == 0
                && variants.getOrDefault(root.key, List.of()).stream().anyMatch(v -> cyclicCraftingCandidates.contains(v.id()))) {
            BigInteger missingSeed = value(result.plan().missing(), root.key);
            if (missingSeed.signum() > 0 && missingSeed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                long borrowed = seedLease.expose(root.key, missingSeed.longValue());
                if (borrowed > 0) {
                    available.put(root.key, BigInteger.valueOf(parent.extract(root.key, Long.MAX_VALUE, Actionable.SIMULATE)));
                    preserved = solveGlobalCyclePlan(variants, root.key, rootItems, available, limits,
                            Map.of(root.key, BigInteger.valueOf(borrowed)));
                    result = preserved.result();
                }
            }
        }
        if (!preserved.converged() || result == null || !result.solved()) {
            return false;
        }

        AelisCyclicDemandSolver.Plan<AEKey, CompiledCandidate> plan = result.plan();
        if (!simulation && !plan.missing().isEmpty()) {
            return false;
        }
        AelisCycleExecutionPlan cycleExecutionPlan = preserved.executionPlan();

        var attempt = new ChildCraftingSimulationState(parent);
        var attemptMissing = new KeyCounter();
        for (var entry : plan.requiredAvailable().entrySet()) {
            long amount = exactPositiveLong(
                    entry.getValue(), "cyclic_required_available_overflow");
            long extracted = attempt.extract(
                    entry.getKey(), amount, Actionable.MODULATE);
            if (extracted != amount) {
                return false;
            }
        }
        for (var entry : plan.missing().entrySet()) {
            long amount = (Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || com.appliedenhancements.runtime.ExactRequestScope.current() != null)
                    ? AelisBigIntegerMath.saturatingLong(entry.getValue())
                    : exactPositiveLong(entry.getValue(), "cyclic_missing_overflow");
            addSaturated(attemptMissing, entry.getKey(), amount);
            recordBigIntegerMissing(attempt, entry.getKey(), entry.getValue());
        }
        for (var entry : plan.demands().entrySet()) {
            if (entry.getValue().signum() <= 0) {
                continue;
            }
            long amount = (Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || com.appliedenhancements.runtime.ExactRequestScope.current() != null)
                    ? AelisBigIntegerMath.saturatingLong(entry.getValue())
                    : exactPositiveLong(entry.getValue(), "cyclic_demand_overflow");
            if (Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || exactRequest != null) {
                ExactCraftingBytes.addStackBytes(attempt, entry.getKey(), entry.getValue());
            } else {
                attempt.addStackBytes(entry.getKey(), 1, amount);
            }
        }
        for (var entry : plan.firings().entrySet()) {
            long times = cycleProjection(entry.getValue(), "cyclic_firing_overflow");
            CompiledCandidate candidate = entry.getKey();
            if (candidate.observedPatternSemantics != null
                    && !candidate.observedPatternSemantics.matches(candidate.details)) {
                return false;
            }
            if (candidate.sourceProcess == null) {
                if (!rawCyclePatternStillAvailable(
                        graph.craftingService, candidate.details)) {
                    return false;
                }
                for (RawCycleInput rawInput : candidate.rawCycleInputs) {
                    if (!validateRawCycleInput(rawInput)) {
                        return false;
                    }
                }
            }
            if (Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || exactRequest != null) {
                recordProjectedBigIntegerCrafting(attempt, candidate.details, entry.getValue(),
                        projectedPatternTimes(attempt, candidate.details, entry.getValue()));
            } else {
                attempt.addCrafting(candidate.details, times);
            }
            if (cyclicCraftingCandidates.contains(candidate)) {
                ((AelisCyclicCraftingTracker) attempt)
                        .appliedenhancements$recordCyclicCrafting(
                                candidate.details, entry.getValue());
            }
            ExactCraftingBytes.addBytes(attempt, entry.getValue());
        }
        for (var entry : plan.surplus().entrySet()) {
            BigInteger unusedInitial = value(available, entry.getKey())
                    .subtract(value(plan.requiredAvailable(), entry.getKey()));
            BigInteger insert = entry.getValue().subtract(unusedInitial.max(BigInteger.ZERO));
            if (insert.signum() > 0) {
                attempt.insert(
                        entry.getKey(),
                        cycleProjection(insert, "cyclic_surplus_overflow"),
                        Actionable.MODULATE);
            }
        }
        for (var entry : preserved.retainedSeeds().entrySet()) {
            attempt.insert(
                    entry.getKey(),
                    cycleProjection(
                            entry.getValue(), "cyclic_retained_seed_overflow"),
                    Actionable.MODULATE);
        }

        ((AelisCyclicCraftingTracker) attempt)
                .appliedenhancements$recordCycleExecutionPlan(cycleExecutionPlan);

        attempt.applyDiff(parent);
        mergeMissingAmounts(stagedMissing, attemptMissing);
        seedLease.commit();
        return true;
        }
    }

    private static boolean tryExecuteLocalCyclicRegion(
            Graph graph, Node requestedNode,
            CraftingSimulationState parent, BigInteger requestMultipliers,
            long rootRequestedAmount, boolean simulation,
            KeyCounter stagedMissing, PauseCheckpoint pauseCheckpoint,
            ProgressSink progressSink, GraphConsumableInput requestInput)
            throws CraftBranchFailure, InterruptedException {
        try {
            var attempt = new ChildCraftingSimulationState(parent);
            try (var seedLease = AelisIgnoredSeedScope.lease(attempt)) {
            var attemptMissing = new KeyCounter();
            BigInteger requestedItems = cycleProduct(
                    requestedNode.amount, requestMultipliers,
                    "local_cyclic_request_amount_overflow");
            ExactCraftingBytes.addStackBytes(attempt, requestedNode.key, requestedItems);
            if (!validateSparseRootTemplates(
                    requestedNode, attempt, requestInput, pauseCheckpoint)) {
                throw new Fallback("local_cyclic_root_template_changed");
            }

            LocalCyclicRegion local = requestedNode.localCyclicRegion;
            var available = new LinkedHashMap<AEKey, BigInteger>();
            for (AEKey key : local.model.keys()) {
                long amount = attempt.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
                if (amount > 0) {
                    available.put(key, BigInteger.valueOf(amount));
                }
            }
            long budgetNanos = TimeUnit.MILLISECONDS.toNanos(
                    Config.CYCLE_SOLVER_BUDGET_MS.get());
            var limits = new AelisCyclicDemandSolver.Limits(
                    Config.CYCLE_SOLVER_MAX_SCC_NODES.get(),
                    Config.CYCLE_SOLVER_MAX_SEARCH_STATES.get(),
                    saturatedAdd(System.nanoTime(), budgetNanos),
                    Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()
                            || com.appliedenhancements.runtime.ExactRequestScope.current() != null);
            PreservedLocalCyclePlan preserved = solveLocalCyclePlan(
                    local.model, requestedNode.key,
                    requestedItems,
                    available, limits);
            var result = preserved.result();
            if (result != null && result.solved() && value(available, requestedNode.key).signum() == 0) {
                BigInteger missingSeed = value(result.plan().missingSeeds(), requestedNode.key);
                if (missingSeed.signum() > 0 && missingSeed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                    long borrowed = seedLease.expose(requestedNode.key, missingSeed.longValue());
                    if (borrowed > 0) {
                        available.put(requestedNode.key, BigInteger.valueOf(attempt.extract(requestedNode.key, Long.MAX_VALUE, Actionable.SIMULATE)));
                        preserved = solveLocalCyclePlan(local.model, requestedNode.key, requestedItems, available, limits,
                                Map.of(requestedNode.key, BigInteger.valueOf(borrowed)));
                        result = preserved.result();
                    }
                }
            }
            if (!preserved.converged() || result == null || !result.solved()) {
                throw new Fallback(
                        !preserved.converged()
                                ? "local_cyclic_seed_preservation_unstable"
                                : "local_cyclic_solver_"
                                        + result.failure().name().toLowerCase());
            }

            AelisCyclicRegionSolver.Plan<AEKey, CompiledCandidate> plan = result.plan();
            AelisCycleExecutionPlan cycleExecutionPlan = preserved.executionPlan();
            if (!simulation && !plan.missingSeeds().isEmpty()) {
                var missing = plan.missingSeeds().entrySet().iterator().next();
                throw new CraftBranchFailure(
                        missing.getKey(), cycleProjection(
                                missing.getValue(),
                                "local_cyclic_missing_seed_overflow"));
            }
            if (stagedMissing == null && !plan.missingSeeds().isEmpty()) {
                throw new Fallback("local_cyclic_missing_sink_unavailable");
            }

            for (var entry : plan.requiredAvailable().entrySet()) {
                long amount = exactPositiveLong(
                        entry.getValue(),
                        "local_cyclic_required_available_overflow");
                long extracted = attempt.extract(
                        entry.getKey(), amount, Actionable.MODULATE);
                if (extracted != amount) {
                    throw new Fallback("local_cyclic_required_available_changed");
                }
            }
            for (var entry : plan.missingSeeds().entrySet()) {
                long amount = cycleProjection(
                        entry.getValue(), "local_cyclic_missing_overflow");
                addSaturated(attemptMissing, entry.getKey(), amount);
                recordBigIntegerMissing(attempt, entry.getKey(), entry.getValue());
            }

            var externalRequests = new ArrayList<ExternalCyclicRequest>();
            var rawExternalRequests = new ArrayList<RawExternalCyclicRequest>();
            var externalDemands = new LinkedHashMap<AEKey, BigInteger>();
            for (var entry : plan.firings().entrySet()) {
                BigInteger times = entry.getValue();
                CompiledCandidate candidate = entry.getKey();
                if (candidate.observedPatternSemantics != null
                        && !candidate.observedPatternSemantics.matches(
                                candidate.details)) {
                    throw new Fallback("local_cyclic_pattern_semantics_changed");
                }
                if (candidate.sourceProcess == null
                        && !rawCyclePatternStillAvailable(
                                graph.craftingService, candidate.details)) {
                    throw new Fallback("local_cyclic_raw_pattern_unavailable");
                }
                for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
                    if (orderedInput.reusable()) {
                        throw new Fallback("local_cyclic_reusable_input");
                    }
                    GraphConsumableInput consumable = orderedInput.consumableInput;
                    if (consumable == null || consumable.substituteInput
                            || consumable.childIndex < 0
                            || consumable.childIndex >= graph.nodes.size()) {
                        throw new Fallback("local_cyclic_input_changed");
                    }
                    Node child = graph.nodes.get(consumable.childIndex);
                    BigInteger childRequests = cycleProduct(
                            consumable.multiplier, times,
                            "local_cyclic_child_request_overflow");
                    if (!validateSparseConsumableTemplates(
                            child, consumable.child, attempt, pauseCheckpoint)) {
                        throw new Fallback("local_cyclic_input_template_changed");
                    }
                    if (local.model.keys().contains(child.key)) {
                        ExactCraftingBytes.addStackBytes(attempt, child.key,
                                cycleProduct(child.amount, childRequests, "local_cyclic_input_bytes_overflow"));
                        continue;
                    }
                    BigInteger externalItems = cycleProduct(
                            child.amount, childRequests,
                            "local_cyclic_external_amount_overflow");
                    externalDemands.merge(
                            child.key, externalItems,
                            BigInteger::add);
                    externalRequests.add(new ExternalCyclicRequest(
                            consumable, childRequests));
                }
                for (RawCycleInput rawInput : candidate.rawCycleInputs) {
                    if (!validateRawCycleInput(rawInput)) {
                        throw new Fallback("local_cyclic_raw_input_changed");
                    }
                    if (rawInput.childIndex >= 0) {
                        continue;
                    }
                    BigInteger inputRequests = cycleProduct(
                            rawInput.multiplier, times,
                            "local_cyclic_raw_request_overflow");
                    if (local.model.keys().contains(rawInput.key)) {
                        ExactCraftingBytes.addStackBytes(attempt, rawInput.key,
                                cycleProduct(rawInput.amount, inputRequests, "local_cyclic_raw_bytes_overflow"));
                        continue;
                    }
                    BigInteger externalItems = cycleProduct(
                            rawInput.amount, inputRequests,
                            "local_cyclic_raw_external_amount_overflow");
                    externalDemands.merge(
                            rawInput.key, externalItems,
                            BigInteger::add);
                    rawExternalRequests.add(new RawExternalCyclicRequest(
                            rawInput, inputRequests));
                }
            }
            if (!externalDemands.equals(plan.externalDemands())) {
                throw new Fallback("local_cyclic_external_demand_mismatch");
            }

            for (ExternalCyclicRequest external : externalRequests) {
                executeTransactionalNode(
                        graph, external.input.childIndex, attempt,
                        external.requests, rootRequestedAmount, simulation,
                        attemptMissing, pauseCheckpoint, progressSink,
                        -1, null, external.input, false);
            }
            for (RawExternalCyclicRequest external : rawExternalRequests) {
                RawCycleInput input = external.input;
                BigInteger requested = cycleProduct(
                        input.amount, external.requests,
                        "local_cyclic_raw_external_extract_overflow");
                ExactCraftingBytes.addStackBytes(attempt, input.key, requested);
                if (com.appliedenhancements.storage.InfiniteStorageSupport.consume(attempt, input.key, requested)) {
                    continue;
                }
                long extracted = attempt.extract(
                        input.key, cycleProjection(requested, "local_cyclic_raw_external_extract_overflow"), Actionable.MODULATE);
                BigInteger shortage = requested.subtract(BigInteger.valueOf(extracted));
                if (shortage.signum() > 0) {
                    if (!simulation) {
                        throw new CraftBranchFailure(input.key,
                                AelisBigIntegerMath.saturatingLong(shortage));
                    }
                    addSaturated(attemptMissing, input.key, cycleProjection(shortage, "local_cyclic_raw_missing_overflow"));
                    recordBigIntegerMissing(attempt, input.key, shortage);
                }
            }
            for (var entry : plan.firings().entrySet()) {
                long times = cycleProjection(
                        entry.getValue(), "local_cyclic_firing_overflow");
                if (Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()
                        || com.appliedenhancements.runtime.ExactRequestScope.current() != null) {
                    recordProjectedBigIntegerCrafting(attempt, entry.getKey().details, entry.getValue(),
                            projectedPatternTimes(attempt, entry.getKey().details, entry.getValue()));
                } else {
                    attempt.addCrafting(entry.getKey().details, times);
                }
                ((AelisCyclicCraftingTracker) attempt)
                        .appliedenhancements$recordCyclicCrafting(
                                entry.getKey().details, entry.getValue());
                ExactCraftingBytes.addBytes(attempt, entry.getValue());
            }
            for (var entry : plan.surplus().entrySet()) {
                BigInteger unusedInitial = value(available, entry.getKey())
                        .subtract(value(plan.requiredAvailable(), entry.getKey()));
                BigInteger insert = entry.getValue()
                        .subtract(unusedInitial.max(BigInteger.ZERO));
                if (insert.signum() > 0) {
                    attempt.insert(
                            entry.getKey(),
                            cycleProjection(
                                    insert, "local_cyclic_surplus_overflow"),
                            Actionable.MODULATE);
                }
            }
            for (var entry : preserved.retainedSeeds().entrySet()) {
                attempt.insert(
                        entry.getKey(),
                        cycleProjection(
                                entry.getValue(),
                                "local_cyclic_retained_seed_overflow"),
                        Actionable.MODULATE);
            }

            ((AelisCyclicCraftingTracker) attempt)
                    .appliedenhancements$recordCycleExecutionPlan(cycleExecutionPlan);

            attempt.applyDiff(parent);
            if (stagedMissing != null) {
                mergeMissingAmounts(stagedMissing, attemptMissing);
            }
            seedLease.commit();
            return true;
            }
        } catch (Fallback fallback) {
            return false;
        }
    }

    private static boolean rawCyclePatternStillAvailable(
            ICraftingService craftingService, IPatternDetails expected) {
        if (craftingService == null || expected == null) {
            return false;
        }
        try {
            AEKey output = expected.getPrimaryOutput().what();
            for (IPatternDetails current : craftingService.getCraftingFor(output)) {
                if (current == expected || samePatternSemantics(current, expected)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static boolean validateRawCycleInput(RawCycleInput expected) {
        try {
            GenericStack exact = getSingleExactInputChoice(expected.input);
            return exact != null
                    && expected.key.equals(exact.what())
                    && expected.amount == exact.amount()
                    && expected.multiplier == expected.input.getMultiplier()
                    && expected.input.isValid(expected.key, expected.level)
                    && expected.input.getRemainingKey(expected.key) == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean sameCycleCandidateSet(
            List<Node> nodes, List<CompiledCandidate> left,
            List<CompiledCandidate> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            CompiledCandidate first = left.get(index);
            CompiledCandidate second = right.get(index);
            if (!samePatternSemantics(first.details, second.details)
                    || first.outputPerPattern != second.outputPerPattern
                    || first.hasContainerItems != second.hasContainerItems
                    || first.orderedInputs.size() != second.orderedInputs.size()) {
                return false;
            }
            for (int input = 0; input < first.orderedInputs.size(); input++) {
                OrderedGraphInput firstInput = first.orderedInputs.get(input);
                OrderedGraphInput secondInput = second.orderedInputs.get(input);
                if (firstInput.reusable() || secondInput.reusable()) {
                    return false;
                }
                GraphConsumableInput firstConsumable = firstInput.consumableInput;
                GraphConsumableInput secondConsumable = secondInput.consumableInput;
                if (firstConsumable == null || secondConsumable == null
                        || firstConsumable.childIndex < 0
                        || firstConsumable.childIndex >= nodes.size()
                        || secondConsumable.childIndex < 0
                        || secondConsumable.childIndex >= nodes.size()) {
                    return false;
                }
                Node firstChild = nodes.get(firstConsumable.childIndex);
                Node secondChild = nodes.get(secondConsumable.childIndex);
                if (!firstChild.key.equals(secondChild.key)
                        || firstChild.amount != secondChild.amount
                        || firstConsumable.multiplier != secondConsumable.multiplier
                        || firstConsumable.substituteInput
                                != secondConsumable.substituteInput) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsKeyLevelCycle(
            Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>>
                    variants) {
        var indegrees = new LinkedHashMap<AEKey, Integer>();
        var adjacency = new LinkedHashMap<AEKey, Set<AEKey>>();
        for (AEKey key : variants.keySet()) {
            indegrees.put(key, 0);
            adjacency.put(key, new LinkedHashSet<>());
        }
        for (var entry : variants.entrySet()) {
            Set<AEKey> edges = adjacency.get(entry.getKey());
            for (var variant : entry.getValue()) {
                for (var input : variant.inputs()) {
                    if (variants.containsKey(input.key())
                            && edges.add(input.key())) {
                        indegrees.merge(input.key(), 1, Integer::sum);
                    }
                }
            }
        }
        var queue = new ArrayDeque<AEKey>();
        for (var entry : indegrees.entrySet()) {
            if (entry.getValue() == 0) {
                queue.addLast(entry.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            AEKey key = queue.removeFirst();
            visited++;
            for (AEKey target : adjacency.get(key)) {
                int remaining = indegrees.merge(target, -1, Integer::sum);
                if (remaining == 0) {
                    queue.addLast(target);
                }
            }
        }
        return visited != variants.size();
    }

    private static long exactPositiveLong(BigInteger value, String reason)
            throws Fallback {
        if (value == null || value.signum() <= 0
                || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new Fallback(reason);
        }
        return value.longValue();
    }

    private static long cycleProjection(BigInteger value, String reason) throws Fallback {
        return Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()
                || com.appliedenhancements.runtime.ExactRequestScope.current() != null
                ? AelisBigIntegerMath.saturatingLong(value) : exactPositiveLong(value, reason);
    }

    private static BigInteger cycleProduct(long amount, BigInteger multiplier, String reason) throws Fallback {
        if (Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()
                || com.appliedenhancements.runtime.ExactRequestScope.current() != null) {
            return AelisBigIntegerMath.multiply(amount, multiplier);
        }
        return BigInteger.valueOf(checkedMultiply(amount, exactNonNegativeLong(multiplier, reason), reason));
    }

    private static BigInteger cyclicRequestedItems(long amountPerUnit, long requestedAmount) throws Fallback {
        var exactRequest = com.appliedenhancements.runtime.ExactRequestScope.current();
        return cycleProduct(amountPerUnit, exactRequest != null
                ? exactRequest.amount() : BigInteger.valueOf(requestedAmount), "cyclic_root_amount_overflow");
    }

    private static long exactNonNegativeLong(BigInteger value, String reason)
            throws Fallback {
        if (value == null || value.signum() < 0
                || value.compareTo(AelisBigIntegerMath.LONG_MAX) > 0) {
            throw new Fallback(reason);
        }
        return value.longValue();
    }

    private static void addSaturated(KeyCounter counter, AEKey key, long amount) {
        counter.set(key, saturatedAdd(counter.get(key), amount));
    }

    private static void mergeMissingAmounts(KeyCounter target, KeyCounter source) {
        if ((Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || com.appliedenhancements.runtime.ExactRequestScope.current() != null)) {
            for (var entry : source) addSaturated(target, entry.getKey(), entry.getLongValue());
        } else {
            target.addAll(source);
        }
    }

    private static AelisCycleExecutionPlan cycleExecutionPlan(
            List<AelisCyclicDemandSolver.ScheduleStep<AEKey, CompiledCandidate>> schedule,
            Map<AEKey, BigInteger> requiredAvailable) throws Fallback {
        if (schedule == null || schedule.isEmpty()) {
            return null;
        }
        var steps = new ArrayList<AelisCycleExecutionPlan.Step>(schedule.size());
        var protectedKeys = new LinkedHashSet<AEKey>();
        for (var scheduled : schedule) {
            var inputs = new LinkedHashMap<AEKey, Long>();
            for (var entry : scheduled.inputsPerCraft().entrySet()) {
                inputs.put(
                        entry.getKey(),
                        exactPositiveLong(
                                entry.getValue(),
                                "cyclic_runtime_input_overflow"));
            }
            protectedKeys.addAll(inputs.keySet());
            steps.add(new AelisCycleExecutionPlan.Step(
                    scheduled.id().details.getDefinition(),
                    scheduled.firings(),
                    inputs,
                    scheduled.selfReplenishingInputs()));
        }
        var minimumSeeds = new LinkedHashMap<AEKey, Long>();
        for (var entry : requiredAvailable.entrySet()) {
            if (protectedKeys.contains(entry.getKey())
                    && entry.getValue().signum() > 0) {
                minimumSeeds.put(
                        entry.getKey(),
                        exactPositiveLong(
                                entry.getValue(),
                                "cyclic_runtime_seed_overflow"));
            }
        }
        return new AelisCycleExecutionPlan(
                steps, minimumSeeds, protectedKeys,
                Config.CYCLE_SEED_POLICY.get());
    }

    private static PreservedGlobalCyclePlan solveGlobalCyclePlan(
            Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>>
                    variants,
            AEKey rootKey,
            BigInteger rootDemand,
            Map<AEKey, BigInteger> available,
            AelisCyclicDemandSolver.Limits limits) throws Fallback {
        return solveGlobalCyclePlan(variants, rootKey, rootDemand, available, limits, Map.of());
    }

    private static PreservedGlobalCyclePlan solveGlobalCyclePlan(
            Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>> variants,
            AEKey rootKey, BigInteger rootDemand, Map<AEKey, BigInteger> available,
            AelisCyclicDemandSolver.Limits limits, Map<AEKey, BigInteger> borrowedRootSeeds) throws Fallback {
        // Borrowed final-output stock must be returned, not credited toward the new order.
        var retainedSeeds = new LinkedHashMap<AEKey, BigInteger>(borrowedRootSeeds);
        AelisCyclicDemandSolver.Result<AEKey, CompiledCandidate> result = null;
        AelisCycleExecutionPlan executionPlan = null;
        for (int pass = 0; pass < 8; pass++) {
            var demands = new LinkedHashMap<AEKey, BigInteger>();
            demands.put(rootKey, rootDemand);
            for (var entry : retainedSeeds.entrySet()) {
                demands.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
            result = AelisCyclicDemandSolver.solve(
                    new AelisCyclicDemandSolver.Problem<>(
                            variants, demands, available),
                    limits);
            if (!result.solved()) {
                return new PreservedGlobalCyclePlan(
                        result, null, Map.copyOf(retainedSeeds), true);
            }
            var plan = result.plan();
            executionPlan = cycleExecutionPlan(
                    plan.executionSchedule(), plan.requiredAvailable());
            // No cyclic firings means there is no runtime seed floor to reserve.
            if (executionPlan == null
                    || Config.CYCLE_SEED_POLICY.get()
                            == AelisCycleSeedPolicy.MAX_THROUGHPUT) {
                return new PreservedGlobalCyclePlan(
                        result, executionPlan, Map.copyOf(retainedSeeds), true);
            }
            Map<AEKey, BigInteger> additions = AelisCycleSeedReservation.additions(
                    executionPlan.minimumSeeds(), plan.surplus(), retainedSeeds);
            if (additions.isEmpty()) {
                return new PreservedGlobalCyclePlan(
                        result, executionPlan, Map.copyOf(retainedSeeds), true);
            }
            for (var entry : additions.entrySet()) {
                retainedSeeds.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }
        return new PreservedGlobalCyclePlan(
                result, executionPlan, Map.copyOf(retainedSeeds), false);
    }

    private static PreservedLocalCyclePlan solveLocalCyclePlan(
            AelisCyclicRegionDetector.Region<AEKey, CompiledCandidate> region,
            AEKey demandKey,
            BigInteger demand,
            Map<AEKey, BigInteger> available,
            AelisCyclicDemandSolver.Limits limits) throws Fallback {
        return solveLocalCyclePlan(region, demandKey, demand, available, limits, Map.of());
    }

    private static PreservedLocalCyclePlan solveLocalCyclePlan(
            AelisCyclicRegionDetector.Region<AEKey, CompiledCandidate> region,
            AEKey demandKey, BigInteger demand, Map<AEKey, BigInteger> available,
            AelisCyclicDemandSolver.Limits limits, Map<AEKey, BigInteger> borrowedRootSeeds) throws Fallback {
        var retainedSeeds = new LinkedHashMap<AEKey, BigInteger>(borrowedRootSeeds);
        AelisCyclicRegionSolver.Result<AEKey, CompiledCandidate> result = null;
        AelisCycleExecutionPlan executionPlan = null;
        for (int pass = 0; pass < 8; pass++) {
            result = AelisCyclicRegionSolver.solve(
                    region, demandKey, demand,
                    retainedSeeds, available, limits);
            if (!result.solved()) {
                return new PreservedLocalCyclePlan(
                        result, null, Map.copyOf(retainedSeeds), true);
            }
            var plan = result.plan();
            executionPlan = cycleExecutionPlan(
                    plan.executionSchedule(), plan.requiredAvailable());
            // No cyclic firings means there is no runtime seed floor to reserve.
            if (executionPlan == null
                    || Config.CYCLE_SEED_POLICY.get()
                            == AelisCycleSeedPolicy.MAX_THROUGHPUT) {
                return new PreservedLocalCyclePlan(
                        result, executionPlan, Map.copyOf(retainedSeeds), true);
            }
            Map<AEKey, BigInteger> additions = AelisCycleSeedReservation.additions(
                    executionPlan.minimumSeeds(), plan.surplus(), retainedSeeds);
            if (additions.isEmpty()) {
                return new PreservedLocalCyclePlan(
                        result, executionPlan, Map.copyOf(retainedSeeds), true);
            }
            for (var entry : additions.entrySet()) {
                retainedSeeds.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }
        return new PreservedLocalCyclePlan(
                result, executionPlan, Map.copyOf(retainedSeeds), false);
    }

    private record PreservedGlobalCyclePlan(
            AelisCyclicDemandSolver.Result<AEKey, CompiledCandidate> result,
            AelisCycleExecutionPlan executionPlan,
            Map<AEKey, BigInteger> retainedSeeds,
            boolean converged) {
    }

    private record PreservedLocalCyclePlan(
            AelisCyclicRegionSolver.Result<AEKey, CompiledCandidate> result,
            AelisCycleExecutionPlan executionPlan,
            Map<AEKey, BigInteger> retainedSeeds,
            boolean converged) {
    }

    private static <K> BigInteger value(Map<K, BigInteger> values, K key) {
        return values.getOrDefault(key, BigInteger.ZERO);
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
                -1, null, null, false);
    }

    private static void executeTransactionalNode(Graph graph, int nodeIndex,
            CraftingSimulationState inventory, long requestMultipliers,
            long rootRequestedAmount, boolean simulation, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, ProgressSink progressSink,
            int compiledBoundaryIndex, CompiledCandidate compiledCandidate,
            GraphConsumableInput requestInput,
            boolean validateQuantityStock)
            throws Fallback, CraftBranchFailure, InterruptedException {
        executeTransactionalNode(graph, nodeIndex, inventory,
                BigInteger.valueOf(requestMultipliers), rootRequestedAmount,
                simulation, stagedMissing, pauseCheckpoint, progressSink,
                compiledBoundaryIndex, compiledCandidate, requestInput, validateQuantityStock);
    }

    private static void executeTransactionalNode(Graph graph, int nodeIndex,
            CraftingSimulationState inventory, BigInteger requestMultipliers,
            long rootRequestedAmount, boolean simulation, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, ProgressSink progressSink,
            int compiledBoundaryIndex, CompiledCandidate compiledCandidate,
            GraphConsumableInput requestInput,
            boolean validateQuantityStock)
            throws Fallback, CraftBranchFailure, InterruptedException {
        if (!AelisRecursionGuard.tryEnterTransactional()) {
            throw new Fallback("transactional_depth_limit");
        }
        try {
        checkpoint(pauseCheckpoint);
        if (requestMultipliers.signum() <= 0) {
            return;
        }
        progressSink.executionStep();

        Node node = graph.nodes.get(nodeIndex);
        if (!validateQuantityStock
                && node.localCyclicRegion != null
                && (requestInput == null || !requestInput.substituteInput)
                && tryExecuteLocalCyclicRegion(
                        graph, node, inventory, requestMultipliers,
                        rootRequestedAmount, simulation, stagedMissing,
                        pauseCheckpoint, progressSink, requestInput)) {
            return;
        }
        boolean executeCompiledBoundary = nodeIndex == compiledBoundaryIndex;
        if (!executeCompiledBoundary
                && (node.barrier || node.executionMode == ExecutionMode.HYBRID_BARRIER)) {
            long nativeRequest = exactNonNegativeLong(
                    requestMultipliers, "big_integer_native_boundary_overflow");
            if (usesCompiledCandidateTrial(node)) {
                if (tryExecuteCompiledCandidates(
                        graph, nodeIndex, inventory, nativeRequest,
                        rootRequestedAmount, simulation, stagedMissing, pauseCheckpoint, requestInput)) {
                    return;
                }
            }
            if (tryExecuteRuntimeQuantityFeedbackBoundary(
                    graph, nodeIndex, inventory, nativeRequest,
                    rootRequestedAmount, simulation, stagedMissing,
                    pauseCheckpoint, requestInput)) {
                return;
            }
            if ((requestInput == null || !requestInput.substituteInput)
                    && isReusableBoundaryReason(node.barrierReason)
                    && tryExecuteReusableContainerBoundary(
                            node, inventory, nativeRequest, pauseCheckpoint)) {
                return;
            }
            executeNativeBoundary(
                    node, inventory, nativeRequest, requestInput);
            return;
        }

        validateObservedPatternSemantics(
                node, executeCompiledBoundary ? compiledCandidate : null);

        BigInteger requestedItems = AelisBigIntegerMath.multiply(node.amount, requestMultipliers);
        if (!(Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || com.appliedenhancements.runtime.ExactRequestScope.current() != null)) {
            exactNonNegativeLong(requestedItems, "request_amount_overflow");
        }
        ExactCraftingBytes.addStackBytes(inventory, node.key, requestedItems);

        BigInteger remainingMultipliers;
        if (requestInput != null) {
            remainingMultipliers = extractBigIntegerConsumableInputTemplates(
                    node, inventory, requestInput, requestMultipliers, requestedItems,
                    pauseCheckpoint);
        } else {
            validateTemplates(node, inventory, pauseCheckpoint);
            if (com.appliedenhancements.storage.InfiniteStorageSupport.consume(inventory, node.key, requestedItems)) {
                return;
            }
            long available = inventory.extract(
                    node.key, AelisBigIntegerMath.saturatingLong(requestedItems), Actionable.SIMULATE);
            long extractedMultipliers = available / node.amount;
            if (extractedMultipliers > 0) {
                long extractedAmount = node.amount * extractedMultipliers;
                long extracted = inventory.extract(
                        node.key, extractedAmount, Actionable.MODULATE);
                if (extracted != extractedAmount) {
                    throw new IllegalStateException(
                            "Crafting simulation inventory changed during exact extraction");
                }
            }
            remainingMultipliers = requestMultipliers.subtract(BigInteger.valueOf(extractedMultipliers));
        }

        if (remainingMultipliers.signum() == 0) {
            return;
        }
        BigInteger totalRequestedItems = AelisBigIntegerMath.multiply(node.amount, remainingMultipliers);
        if (node.emitter) {
            inventory.emitItems(node.key, exactNonNegativeLong(
                    totalRequestedItems, "big_integer_emitted_amount_overflow"));
            return;
        }
        if (node.terminal) {
            if (!simulation) {
                throw new CraftBranchFailure(node.key, AelisBigIntegerMath.saturatingLong(totalRequestedItems));
            }
            if (stagedMissing == null) {
                throw new Fallback("missing_terminal_input");
            }
            addSaturated(stagedMissing, node.key, AelisBigIntegerMath.saturatingLong(totalRequestedItems));
            recordBigIntegerMissing(inventory, node.key, totalRequestedItems);
            return;
        }

        CompiledCandidate activeCandidate = executeCompiledBoundary
                ? compiledCandidate
                : node.compiledCandidates.size() == 1
                        ? node.compiledCandidates.get(0)
                        : null;
        if (!validateQuantityStock
                && (requestInput == null || !requestInput.substituteInput)
                && activeCandidate != null
                && activeCandidate.quantityFeedbackProfile != null) {
            long availableItems = requestedItems.subtract(totalRequestedItems).longValueExact();
            executeQuantityFeedbackNode(
                    graph, node, activeCandidate, inventory,
                    exactNonNegativeLong(requestedItems, "big_integer_feedback_boundary_overflow"), availableItems,
                    rootRequestedAmount, simulation, stagedMissing,
                    pauseCheckpoint, progressSink);
            return;
        }

        long effectiveOutputPerPattern = executeCompiledBoundary
                ? compiledCandidate.outputPerPattern
                : node.outputPerPattern;
        if (effectiveOutputPerPattern <= 0) {
            com.appliedenhancements.runtime.AelisPlanningLog.warning(
                    "invalid_transactional_output_per_pattern",
                    "AELIS rejected invalid transactional outputPerPattern: key={}, amount={}, barrier={}, barrierReason={}, executionMode={}, logicalOccurrences={}",
                    node.key, node.amount, node.barrier, node.barrierReason,
                    node.executionMode, node.logicalOccurrences);
            throw new Fallback("invalid_output_per_pattern");
        }
        BigInteger patternTimes = AelisBigIntegerMath.ceilDiv(totalRequestedItems, effectiveOutputPerPattern);
        if (validateQuantityStock) {
            validateRuntimeQuantityFeedbackStock(
                    graph, node, compiledCandidate, inventory,
                    exactNonNegativeLong(remainingMultipliers, "big_integer_feedback_boundary_overflow"),
                    exactNonNegativeLong(patternTimes, "big_integer_feedback_boundary_overflow"), pauseCheckpoint);
        }
        var returnedReusableInputs = new KeyCounter();
        List<OrderedGraphInput> selectedInputs = executeCompiledBoundary
                ? compiledCandidate.orderedInputs
                : node.orderedInputs;
        for (OrderedGraphInput orderedInput : selectedInputs) {
            checkpoint(pauseCheckpoint);
            if (orderedInput.reusable()) {
                GraphReusableInput reusableInput = orderedInput.reusableInput;
                if (reusableInput.mode == BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                    if (!allocateDeterministicDamageInput(
                            inventory, reusableInput.input, reusableInput.child,
                            reusableInput.multiplier, exactNonNegativeLong(
                                    patternTimes, "big_integer_damage_input_overflow"), pauseCheckpoint)) {
                        throw new Fallback("deterministic_damage_input_unavailable");
                    }
                } else if (reusableInput.mode != BoundaryInputMode.INVARIANT_REUSABLE
                        || !leaseInvariantReusableInput(
                                inventory, reusableInput, patternTimes,
                                returnedReusableInputs, pauseCheckpoint)) {
                    throw new Fallback("reusable_input_unavailable");
                }
            } else {
                GraphConsumableInput consumableInput = orderedInput.consumableInput;
                BigInteger childRequests = AelisBigIntegerMath.multiply(
                        consumableInput.multiplier, patternTimes);
                executeTransactionalNode(
                        graph, consumableInput.childIndex, inventory,
                        childRequests, rootRequestedAmount, simulation,
                        stagedMissing, pauseCheckpoint,
                        progressSink, -1, null, consumableInput, false);
            }
        }

        for (var stack : returnedReusableInputs) {
            inventory.insert(stack.getKey(), stack.getLongValue(), Actionable.MODULATE);
            var logicalReturns = AelisBigIntegerMath.multiply(stack.getLongValue(), patternTimes);
            ExactCraftingBytes.addStackBytes(inventory, stack.getKey(), logicalReturns);
        }

        long remainder = totalRequestedItems.remainder(BigInteger.valueOf(effectiveOutputPerPattern)).longValueExact();
        long surplus = remainder == 0 ? 0 : effectiveOutputPerPattern - remainder;
        if (surplus > 0) {
            inventory.insert(node.key, surplus, Actionable.MODULATE);
        }
        // AE2 requests every input before inserting any output. Keep that order:
        // a sibling can use these byproducts only after this batch has completed.
        List<GenericStack> secondaryOutputs = executeCompiledBoundary
                ? compiledCandidate.secondaryOutputs : node.secondaryOutputs;
        for (GenericStack output : secondaryOutputs) {
            long amount = exactNonNegativeLong(
                    AelisBigIntegerMath.multiply(output.amount(), patternTimes),
                    "big_integer_secondary_output_overflow");
            long available = inventory.extract(output.what(), Long.MAX_VALUE, Actionable.SIMULATE);
            checkedAdd(available, amount, "secondary_output_inventory_overflow");
            inventory.insert(output.what(), amount, Actionable.MODULATE);
        }
        IPatternDetails pattern = executeCompiledBoundary ? compiledCandidate.details : node.details;
        if ((Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get() || com.appliedenhancements.runtime.ExactRequestScope.current() != null)) {
            long projectedTimes = projectedPatternTimes(inventory, pattern, patternTimes);
            recordProjectedBigIntegerCrafting(inventory, pattern, patternTimes, projectedTimes);
        } else {
            inventory.addCrafting(pattern, exactNonNegativeLong(patternTimes, "pattern_count_overflow"));
        }
        ExactCraftingBytes.addBytes(inventory, patternTimes);
        } finally {
            AelisRecursionGuard.exitTransactional();
        }
    }

    private static void executeQuantityFeedbackNode(
            Graph graph, Node node, CompiledCandidate candidate,
            CraftingSimulationState inventory,
            long requestedItems, long availableItems,
            long rootRequestedAmount, boolean simulation,
            KeyCounter stagedMissing, PauseCheckpoint pauseCheckpoint,
            ProgressSink progressSink)
            throws Fallback, CraftBranchFailure, InterruptedException {
        AelisQuantityFeedbackBatch.Plan plan;
        try {
            plan = AelisQuantityFeedbackBatch.plan(
                    requestedItems, availableItems,
                    candidate.quantityFeedbackProfile,
                    Config.CYCLE_SEED_POLICY.get());
        } catch (IllegalArgumentException exception) {
            throw new Fallback("quantity_feedback_plan_invalid");
        }
        if (plan.missingSeedItems() > 0) {
            if (!simulation) {
                throw new CraftBranchFailure(
                        node.key, plan.missingSeedItems());
            }
            if (stagedMissing == null) {
                throw new Fallback("quantity_feedback_missing_seed");
            }
            checkedAdd(
                    stagedMissing.get(node.key), plan.missingSeedItems(),
                    "quantity_feedback_missing_overflow");
            stagedMissing.add(node.key, plan.missingSeedItems());
            recordBigIntegerMissing(inventory, node.key, BigInteger.valueOf(plan.missingSeedItems()));
        }

        boolean feedbackSeen = false;
        for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
            checkpoint(pauseCheckpoint);
            if (orderedInput.reusable()) {
                throw new Fallback("quantity_feedback_reusable_input");
            }
            GraphConsumableInput consumable = orderedInput.consumableInput;
            long childRequests = checkedMultiply(
                    consumable.multiplier, plan.patternTimes(),
                    "quantity_feedback_child_request_overflow");
            if (consumable.quantityFeedbackInput) {
                Node feedback = graph.nodes.get(consumable.childIndex);
                long feedbackItemsPerPattern = checkedMultiply(
                        feedback.amount, consumable.multiplier,
                        "quantity_feedback_input_overflow");
                if (!node.key.equals(feedback.key)
                        || feedback.amount <= 0
                        || feedbackSeen
                        || feedbackItemsPerPattern
                                != candidate.quantityFeedbackProfile
                                        .selfItemsPerPattern()) {
                    throw new Fallback("quantity_feedback_input_changed");
                }
                feedbackSeen = true;
                if (childRequests > 0) {
                    inventory.addStackBytes(
                            feedback.key, feedback.amount, childRequests);
                }
                continue;
            }
            if (childRequests > 0) {
                executeTransactionalNode(
                        graph, consumable.childIndex, inventory,
                        childRequests, rootRequestedAmount, simulation,
                        stagedMissing, pauseCheckpoint, progressSink,
                        -1, null, consumable, false);
            }
        }
        if (!feedbackSeen) {
            throw new Fallback("quantity_feedback_input_missing");
        }

        if (plan.surplusItems() > 0) {
            inventory.insert(
                    node.key, plan.surplusItems(), Actionable.MODULATE);
        }
        if (plan.patternTimes() > 0) {
            inventory.addCrafting(candidate.details, plan.patternTimes());
            var tracker = (AelisCyclicCraftingTracker) inventory;
            tracker.appliedenhancements$recordCyclicCrafting(
                    candidate.details, plan.patternTimes());
            long selfItems = candidate.quantityFeedbackProfile
                    .selfItemsPerPattern();
            tracker.appliedenhancements$recordCycleExecutionPlan(
                    new AelisCycleExecutionPlan(
                            List.of(new AelisCycleExecutionPlan.Step(
                                    candidate.details.getDefinition(),
                                    plan.patternTimes(),
                                    Map.of(node.key, selfItems),
                                    Set.of(node.key))),
                            Map.of(node.key, selfItems),
                            Set.of(node.key),
                            Config.CYCLE_SEED_POLICY.get()));
            ExactCraftingBytes.addBytes(inventory, plan.patternTimes());
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

        CompiledCandidate candidate = node.compiledCandidates.get(0);
        if (!candidate.quantityFeedbackBatch) {
            return false;
        }

        var attemptInventory = new ChildCraftingSimulationState(parent);
        var attemptMissing = new KeyCounter();
        try {
            executeTransactionalNode(
                    graph, nodeIndex, attemptInventory, requestMultipliers,
                    rootRequestedAmount, simulation, attemptMissing, pauseCheckpoint,
                    ProgressSink.NONE, nodeIndex, candidate, requestInput,
                    true);
            validateKeyCounterMerge(
                    stagedMissing, attemptMissing,
                    "quantity_feedback_missing_overflow");
        } catch (Fallback fallback) {
            return false;
        }

        attemptInventory.applyDiff(parent);
        mergeMissingAmounts(stagedMissing, attemptMissing);
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
            long expectedPatternTimes, PauseCheckpoint pauseCheckpoint)
            throws Fallback, InterruptedException {
        if (candidate == null || !candidate.quantityFeedbackBatch
                || !candidate.limitsQuantity || owner.amount != 1
                || candidate.hasContainerItems
                || candidate.orderedInputs.isEmpty()) {
            throw new Fallback("quantity_feedback_runtime_shape");
        }

        var inputs = new ArrayList<AelisQuantityFeedbackRuntimeBatch.Input<AEKey>>(
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
            inputs.add(new AelisQuantityFeedbackRuntimeBatch.Input<>(
                    child.key, child.amount, consumable.multiplier, feedback));
        }

        var plan = AelisQuantityFeedbackRuntimeBatch.plan(
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
    }

    private static boolean usesCompiledCandidateTrial(Node node) {
        return AelisExecutionPolicy.selectBoundary(node.barrierReason)
                == AelisExecutionPolicy.BoundaryStrategy.COMPILED_CANDIDATES_THEN_NATIVE;
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
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput)
            throws CraftBranchFailure, InterruptedException, Fallback {
        Node node = graph.nodes.get(nodeIndex);
        boolean certifiedPrefixProbe =
                AelisFirstCandidatePrefixProbeScope.active();
        boolean deterministicCandidates = !certifiedPrefixProbe
                && hasDeterministicCandidateSubgraph(graph, node);
        CraftingTreeNode candidateRoot = node.occurrences.isEmpty()
                ? null
                : node.occurrences.get(0);
        CandidateOccurrenceCheck entryCandidateCheck =
                inspectSimulationFirstCandidateOccurrence(
                        node, candidateRoot, true, true, simulation);
        if (!entryCandidateCheck.safe) {
            if (certifiedPrefixProbe) {
                throw new CertifiedPrefixProbeFallback(
                        "stale_or_impossible_first_candidate");
            }
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
                : SimulationFirstCandidateProof.accept(List.of());
        CompiledCandidate firstCandidate = node.compiledCandidates.get(0);
        boolean singlePatternSimulationShape = simulation
                && exactRequest
                && !simulationProof.safe
                && isLiveSimulationRequestedOccurrence(
                        node, requestInput, candidateRoot)
                && hasVerifiedPatternSemantics(firstCandidate)
                && hasOnlyExactPrimaryOutputs(node, firstCandidate)
                && hasExactStatelessCandidateInputs(firstCandidate)
                && validateSparseRootTemplates(
                        node, parent, requestInput, pauseCheckpoint);
        boolean singlePatternSimulation = singlePatternSimulationShape
                && AelisSimulationSinglePattern.isAtMostOnePattern(
                        node.amount, requestMultipliers,
                        firstCandidate.outputPerPattern);
        boolean boundedPatternReplay = singlePatternSimulationShape
                && !singlePatternSimulation
                && AelisSimulationSinglePattern.canReplayWithin(
                        node.amount, requestMultipliers,
                        firstCandidate.outputPerPattern,
                        MAX_SIMULATION_ORDERED_REPLAY_STEPS);
        boolean simulationCandidateBatchValid = !simulation
                || exactRequest && (simulationProof.safe
                        || singlePatternSimulation);
        if (!AelisOrderedChoiceFallback.mayCommitCompiledResult(
                simulation, simulationCandidateBatchValid)) {
            if (singlePatternSimulationShape
                    && tryExecuteSparseSimulationFirstCandidate(
                            graph, nodeIndex, firstCandidate, parent,
                            requestMultipliers, rootRequestedAmount,
                            stagedMissing, pauseCheckpoint, requestInput,
                            candidateRoot)) {
                return true;
            }
            if (boundedPatternReplay
                    && tryExecuteSimulationFirstCandidateLinearly(
                            graph, nodeIndex, firstCandidate, parent,
                            requestMultipliers, rootRequestedAmount,
                            stagedMissing, pauseCheckpoint, requestInput,
                            candidateRoot)) {
                return true;
            }
            return false;
        }
        boolean directStockCandidateSet = !certifiedPrefixProbe
                && exactRequest
                && hasDirectStockCandidateSet(graph, node);
        boolean safeCandidateMix = !certifiedPrefixProbe
                && hasLiveCandidateSet(node, candidateRoot)
                && (deterministicCandidates || directStockCandidateSet);
        boolean batchCandidateMix = !certifiedPrefixProbe
                && AelisExecutionPolicy.canBatchCandidateMix(
                        simulation, node.allCandidatesCompiled,
                        node.compiledCandidates.size(), node.candidatePatterns.size(),
                        safeCandidateMix);
        int candidateLimit = certifiedPrefixProbe
                ? 1
                : AelisExecutionPolicy.compiledCandidateTrialLimit(
                        simulation, deterministicCandidates,
                        simulation
                                ? simulationCandidateBatchValid
                                : firstCandidateBatchValid,
                        node.compiledCandidates.size());
        if (!batchCandidateMix && candidateLimit == 0) {
            return false;
        }

        // Snapshotting walks the built AE2 candidate tree. Defer it until a
        // compiled attempt will actually run so direct-native routing remains
        // O(1) in the size of that tree.
        var possibleSnapshot = snapshotPossibleStates(candidateRoot);
        enableSimulationCandidateRecovery(
                simulationProof, possibleSnapshot);
        if (batchCandidateMix) {
            return tryExecuteCompiledCandidateMix(
                    graph, nodeIndex, parent, requestMultipliers,
                    rootRequestedAmount,
                    stagedMissing, pauseCheckpoint, requestInput,
                    candidateRoot, possibleSnapshot,
                    !deterministicCandidates && directStockCandidateSet);
        }

        if (candidateLimit == 1) {
            CompiledCandidate candidate = node.compiledCandidates.get(0);
            CandidateAttempt attempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, parent, requestMultipliers,
                    rootRequestedAmount, simulation, pauseCheckpoint, requestInput,
                    candidateRoot, possibleSnapshot);
            preserveRecoveredCandidateStates(
                    attempt, simulationProof, possibleSnapshot);
            if (attempt.status == CandidateAttemptStatus.APPLIED) {
                attempt.inventory.applyDiff(parent);
                mergeMissingAmounts(stagedMissing, attempt.missing);
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
                    && AelisExecutionPolicy
                            .shouldTrySparseCandidateSetAfterFirstShortage(
                                    simulation, exactRequest,
                                    node.allCandidatesCompiled,
                                    node.compiledCandidates.size(),
                                    node.candidatePatterns.size(),
                                    hasLiveCandidateSet(node, candidateRoot))) {
                boolean applied = tryExecuteCompiledCandidateMix(
                        graph, nodeIndex, parent, requestMultipliers,
                        rootRequestedAmount, stagedMissing, pauseCheckpoint, requestInput,
                        candidateRoot, possibleSnapshot, true);
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
                            rootRequestedAmount, pauseCheckpoint,
                            requestInput, candidateRoot)) {
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
                        stagedMissing, pauseCheckpoint, requestInput,
                        candidateRoot, possibleSnapshot)) {
                    return true;
                }
            }
            return false;
        }
        return AelisOrderedCandidateTrial.tryInOrder(
                candidateLimit, compiledIndex -> {
            CompiledCandidate candidate = node.compiledCandidates.get(compiledIndex);
            CandidateAttempt attempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, parent, requestMultipliers,
                    rootRequestedAmount, simulation, pauseCheckpoint, requestInput,
                    candidateRoot, possibleSnapshot);
            if (attempt.status == CandidateAttemptStatus.APPLIED) {
                attempt.inventory.applyDiff(parent);
                mergeMissingAmounts(stagedMissing, attempt.missing);
                restorePossibleStates(candidateRoot, attempt.possibleStates);
                return true;
            }
            return false;
        });
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
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot)
            throws CraftBranchFailure, InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        List<CraftingTreeProcess> processes =
                getNativeAggregateCandidateSet(node, candidateRoot);
        if (processes == null) {
            return false;
        }

        var originalPossible = snapshotPossibleStates(candidateRoot);
        var workingPossible = originalPossible;
        var mixedInventory = new ChildCraftingSimulationState(parent);
        long remaining = requestMultipliers;
        for (int candidateIndex = 0;
                candidateIndex < processes.size() && remaining > 0;
                candidateIndex++) {
            checkpoint(pauseCheckpoint);
            CraftingTreeProcess process = processes.get(candidateIndex);
            var processBridge = (AelisCraftingTreeProcessBridge) process;
            if (!processBridge.molecularmanipulator$isPossible()) {
                continue;
            }

            final long candidateRequest = remaining;
            final IdentityHashMap<CraftingTreeProcess, Boolean> probeSnapshot =
                    workingPossible;
            CandidateAttempt fullAttempt = executeNativeAggregateCandidate(
                    node, process, mixedInventory, candidateRequest,
                    pauseCheckpoint, requestInput, candidateRoot,
                    probeSnapshot);
            if (fullAttempt.status == CandidateAttemptStatus.FALLBACK) {
                restorePossibleStates(candidateRoot, originalPossible);
                return false;
            }

            long allocation;
            CandidateAttempt best;
            if (fullAttempt.status == CandidateAttemptStatus.APPLIED) {
                allocation = candidateRequest;
                best = fullAttempt;
            } else {
                AelisMaximumSuccessfulPrefix.Result<CandidateAttempt> prefix =
                        AelisMaximumSuccessfulPrefix.find(
                            candidateRequest, trialAmount -> {
                                CandidateAttempt trial = executeNativeAggregateCandidate(
                                        node, process, mixedInventory, trialAmount,
                                        pauseCheckpoint, requestInput, candidateRoot,
                                        probeSnapshot);
                                return switch (trial.status) {
                                    case APPLIED ->
                                            AelisMaximumSuccessfulPrefix.ProbeResult
                                                    .applied(trial);
                                    case SHORTAGE ->
                                            AelisMaximumSuccessfulPrefix.ProbeResult
                                                    .shortage();
                                    case FALLBACK ->
                                            AelisMaximumSuccessfulPrefix.ProbeResult
                                                    .fallback();
                                };
                            });
                if (prefix.fallback()) {
                    restorePossibleStates(candidateRoot, originalPossible);
                    return false;
                }
                allocation = prefix.allocation();
                best = prefix.value();
            }
            if (allocation <= 0 || best == null) {
                continue;
            }

            best.inventory.applyDiff(mixedInventory);
            restorePossibleStates(candidateRoot, best.possibleStates);
            workingPossible = best.possibleStates;
            remaining -= allocation;
        }

        if (remaining > 0) {
            restorePossibleStates(candidateRoot, originalPossible);
            throw new CraftBranchFailure(
                    node.key, saturatedMultiply(node.amount, remaining));
        }

        mixedInventory.applyDiff(parent);
        restorePossibleStates(candidateRoot, workingPossible);
        return true;
    }

    private static List<CraftingTreeProcess> getNativeAggregateCandidateSet(
            Node node, CraftingTreeNode candidateRoot) {
        if (candidateRoot == null) {
            return null;
        }
        try {
            List<CraftingTreeProcess> processes =
                    ((AelisCraftingTreeNodeBridge) candidateRoot)
                            .molecularmanipulator$getProcesses();
            processes = withoutNoProgressCandidates(node, processes);
            if (processes == null || processes.size() <= 1
                    || processes.size() != node.candidatePatterns.size()) {
                return null;
            }
            for (int index = 0; index < processes.size(); index++) {
                CraftingTreeProcess process = processes.get(index);
                var bridge = (AelisCraftingTreeProcessBridge) process;
                if (bridge.molecularmanipulator$getDetails()
                        != node.candidatePatterns.get(index)) {
                    return null;
                }
                if (bridge.molecularmanipulator$hasContainerItems()) {
                    return null;
                }
                if (bridge.molecularmanipulator$limitsQuantity()) {
                    return null;
                }
                if (bridge.molecularmanipulator$hasMultiplePaths()) {
                    return null;
                }
                if (getExactCandidateOutputCount(
                        node.key,
                        bridge.molecularmanipulator$getDetails()) <= 0) {
                    return null;
                }
            }
            return List.copyOf(processes);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static CandidateAttempt executeNativeAggregateCandidate(
            Node node, CraftingTreeProcess process,
            CraftingSimulationState parent, long requestMultipliers,
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot)
            throws InterruptedException {
        var candidateInventory = new ChildCraftingSimulationState(parent);
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
                var bridge = (AelisCraftingTreeProcessBridge) process;
                long outputPerPattern = getExactCandidateOutputCount(
                        node.key, bridge.molecularmanipulator$getDetails());
                if (outputPerPattern <= 0) {
                    return new CandidateAttempt(
                            CandidateAttemptStatus.FALLBACK, null, null, null);
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
                    return new CandidateAttempt(
                            CandidateAttemptStatus.FALLBACK, null, null, null);
                }
            }

            return new CandidateAttempt(
                    CandidateAttemptStatus.APPLIED, candidateInventory, new KeyCounter(),
                    snapshotPossibleStates(candidateRoot));
        } catch (CraftBranchFailure failure) {
            return new CandidateAttempt(CandidateAttemptStatus.SHORTAGE, null, null, null);
        } catch (Fallback fallback) {
            return new CandidateAttempt(CandidateAttemptStatus.FALLBACK, null, null, null);
        } finally {
            restorePossibleStates(candidateRoot, possibleSnapshot);
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
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot)
            throws InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        AelisSparseCapacitySolver.Plan plan = tryPlanSparseCandidateMix(
                graph, nodeIndex, parent, requestMultipliers,
                requestInput, pauseCheckpoint, true);
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
                candidateRoot, possibleSnapshot);
        if (attempt.status != CandidateAttemptStatus.APPLIED) {
            restorePossibleStates(candidateRoot, possibleSnapshot);
            return false;
        }

        attempt.inventory.applyDiff(parent);
        mergeMissingAmounts(stagedMissing, attempt.missing);
        restorePossibleStates(candidateRoot, attempt.possibleStates);
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
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot)
            throws CraftBranchFailure, InterruptedException, Fallback {
        Node node = graph.nodes.get(nodeIndex);
        if (!AelisSimulationSinglePattern.canReplayWithin(
                node.amount, requestMultipliers, candidate.outputPerPattern,
                MAX_SIMULATION_ORDERED_REPLAY_STEPS)) {
            return false;
        }
        long chunk = AelisSimulationSinglePattern
                .maxRequestMultipliersPerPattern(
                        node.amount, candidate.outputPerPattern);
        if (chunk <= 0) {
            return false;
        }

        long remaining = requestMultipliers;
        long committed = 0;
        var possibleSnapshot = snapshotPossibleStates(candidateRoot);
        while (remaining > 0) {
            checkpoint(pauseCheckpoint);
            long trialAmount = Math.min(remaining, chunk);
            CandidateAttempt attempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, parent, trialAmount,
                    rootRequestedAmount, true, pauseCheckpoint, requestInput,
                    candidateRoot, possibleSnapshot);
            if (attempt.status != CandidateAttemptStatus.APPLIED) {
                if (committed == 0) {
                    return false;
                }
                executeNativeBoundary(
                        node, parent, remaining, requestInput);
                return true;
            }

            attempt.inventory.applyDiff(parent);
            mergeMissingAmounts(stagedMissing, attempt.missing);
            restorePossibleStates(candidateRoot, attempt.possibleStates);
            possibleSnapshot = attempt.possibleStates;
            remaining -= trialAmount;
            committed += trialAmount;
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
            PauseCheckpoint pauseCheckpoint,
            GraphConsumableInput requestInput, CraftingTreeNode candidateRoot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot)
            throws CraftBranchFailure, InterruptedException, Fallback {
        Node node = graph.nodes.get(nodeIndex);
        AelisMaximumSuccessfulPrefix.Result<CandidateAttempt> prefix =
                AelisMaximumSuccessfulPrefix.find(
                        requestMultipliers, trialAmount -> {
                            CandidateAttempt trial;
                            try (var ignored =
                                    AelisFirstCandidatePrefixProbeScope.enter()) {
                                trial = executeCompiledCandidate(
                                        graph, nodeIndex, candidate, parent,
                                        trialAmount, rootRequestedAmount, false,
                                        pauseCheckpoint, requestInput, candidateRoot,
                                        possibleSnapshot);
                            }
                            return switch (trial.status) {
                                case APPLIED ->
                                        AelisMaximumSuccessfulPrefix.ProbeResult
                                                .applied(trial);
                                case SHORTAGE ->
                                        AelisMaximumSuccessfulPrefix.ProbeResult
                                                .shortage();
                                case FALLBACK ->
                                        AelisMaximumSuccessfulPrefix.ProbeResult
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

        CandidateAttempt best = prefix.value();
        best.inventory.applyDiff(parent);
        mergeMissingAmounts(stagedMissing, best.missing);
        restorePossibleStates(candidateRoot, best.possibleStates);

        executeNativeBoundary(
                node, parent, remaining,
                requestInput);
        return true;
    }

    private static boolean tryExecuteCompiledCandidateMix(Graph graph, int nodeIndex,
            CraftingSimulationState parent, long requestMultipliers,
            long rootRequestedAmount, KeyCounter stagedMissing,
            PauseCheckpoint pauseCheckpoint, GraphConsumableInput requestInput,
            CraftingTreeNode candidateRoot,
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot, boolean requireSparseCapacityPlan)
            throws CraftBranchFailure, InterruptedException {
        Node node = graph.nodes.get(nodeIndex);
        var mixedInventory = new ChildCraftingSimulationState(parent);
        var mixedMissing = new KeyCounter();
        AelisSparseCapacitySolver.Plan sparsePlan = tryPlanSparseCandidateMix(
                graph, nodeIndex, mixedInventory, requestMultipliers,
                requestInput, pauseCheckpoint, false);
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
                CandidateAttempt attempt = executeCompiledCandidate(
                        graph, nodeIndex, candidate, mixedInventory, allocation,
                        rootRequestedAmount, false, pauseCheckpoint, requestInput,
                        candidateRoot, candidateSnapshot);
                if (attempt.status != CandidateAttemptStatus.APPLIED) {
                    restorePossibleStates(candidateRoot, possibleSnapshot);
                    return false;
                }
                attempt.inventory.applyDiff(mixedInventory);
                mergeMissingAmounts(mixedMissing, attempt.missing);
                restorePossibleStates(candidateRoot, attempt.possibleStates);
            }

            mixedInventory.applyDiff(parent);
            mergeMissingAmounts(stagedMissing, mixedMissing);
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
            CandidateAttempt fullAttempt = executeCompiledCandidate(
                    graph, nodeIndex, candidate, mixedInventory, remaining,
                    rootRequestedAmount, false, pauseCheckpoint, requestInput,
                    candidateRoot, candidateSnapshot);
            if (fullAttempt.status == CandidateAttemptStatus.FALLBACK) {
                restorePossibleStates(candidateRoot, possibleSnapshot);
                return false;
            }
            if (fullAttempt.status == CandidateAttemptStatus.APPLIED) {
                fullAttempt.inventory.applyDiff(mixedInventory);
                mergeMissingAmounts(mixedMissing, fullAttempt.missing);
                restorePossibleStates(candidateRoot, fullAttempt.possibleStates);
                remaining = 0;
                break;
            }

            long low = 0;
            long high = remaining;
            CandidateAttempt bestAttempt = null;
            while (high - low > 1) {
                checkpoint(pauseCheckpoint);
                long trialAmount = AelisExecutionPolicy.upperMidpoint(
                        low, high);
                CandidateAttempt trial = executeCompiledCandidate(
                        graph, nodeIndex, candidate, mixedInventory, trialAmount,
                        rootRequestedAmount, false, pauseCheckpoint, requestInput,
                        candidateRoot, candidateSnapshot);
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
                mergeMissingAmounts(mixedMissing, bestAttempt.missing);
                restorePossibleStates(candidateRoot, bestAttempt.possibleStates);
                remaining -= low;
            }
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
        mergeMissingAmounts(stagedMissing, mixedMissing);
        return true;
    }

    /**
     * Builds a compact, exact-key model for the requested candidate subtree and
     * snapshots every involved inventory key once. Unsupported runtime
     * semantics return {@code null} and keep the established transactional
     * implementation as the compatibility path.
     */
    private static AelisSparseCapacitySolver.Plan tryPlanSparseCandidateMix(
            Graph graph, int nodeIndex, CraftingSimulationState inventory,
            long requestMultipliers, GraphConsumableInput requestInput,
            PauseCheckpoint pauseCheckpoint,
            boolean simulationFirstCandidate)
            throws InterruptedException {
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
            return null;
        }
        AelisCraftingTreeNodeBridge rootOccurrence;
        try {
            rootOccurrence = requestInput == null
                    ? (AelisCraftingTreeNodeBridge) graph.nodes.get(nodeIndex)
                            .occurrences.get(0)
                    : requestInput.child;
        } catch (RuntimeException exception) {
            return null;
        }
        if (!collectSparseCapacitySubgraph(
                        graph, nodeIndex, rootOccurrence, inventory,
                        usedNodes, states, liveTerminalNodes,
                        reusableKeys, consumableTemplates, templateKeys,
                        validatedReusableInputs, pauseCheckpoint,
                        simulationFirstCandidate)) {
            return null;
        }
        if (!validateSparseSubstituteGraphKeys(
                graph, usedNodes, consumableTemplates)) {
            return null;
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
            return null;
        }

        var modelNodes = new AelisSparseCapacitySolver.Node[graph.nodes.size()];
        int fallbackKeyIndex = 0;
        for (int index = 0; index < graph.nodes.size(); index++) {
            Node graphNode = graph.nodes.get(index);
            Integer keyIndex = keyIndexes.get(graphNode.key);
            if (!usedNodes[index] || keyIndex == null) {
                modelNodes[index] = AelisSparseCapacitySolver.Node.unsupported(
                        fallbackKeyIndex, Math.max(1, graphNode.amount));
                continue;
            }
            if (graphNode.emitter) {
                modelNodes[index] = AelisSparseCapacitySolver.Node.emitter(
                        keyIndex, graphNode.amount);
                continue;
            }
            if (graphNode.terminal || liveTerminalNodes[index]) {
                modelNodes[index] = AelisSparseCapacitySolver.Node.terminal(
                        keyIndex, graphNode.amount);
                continue;
            }

            List<CompiledCandidate> candidates = getSparseCapacityCandidates(
                    graph, graphNode, simulationFirstCandidate);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            var modelCandidates =
                    new AelisSparseCapacitySolver.Candidate[candidates.size()];
            for (int candidateIndex = 0;
                    candidateIndex < candidates.size(); candidateIndex++) {
                modelCandidates[candidateIndex] = createSparseCapacityCandidate(
                        candidates.get(candidateIndex), keyIndexes,
                        consumableTemplates);
                if (modelCandidates[candidateIndex] == null) {
                    return null;
                }
            }
            modelNodes[index] = AelisSparseCapacitySolver.Node.craftable(
                    keyIndex, graphNode.amount, modelCandidates);
        }

        var snapshot = new long[keyIndexes.size()];
        for (var entry : keyIndexes.entrySet()) {
            checkpoint(pauseCheckpoint);
            snapshot[entry.getValue()] = inventory.extract(
                    entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
        }

        AelisSparseCapacitySolver.Plan plan;
        try {
            var model = new AelisSparseCapacitySolver.Model(
                    keyIndexes.size(), modelNodes);
            plan = simulationFirstCandidate
                    ? AelisSparseCapacitySolver.planSimulationFirstCandidate(
                            model, nodeIndex, requestMultipliers, snapshot)
                    : AelisSparseCapacitySolver.plan(
                            model, nodeIndex, requestMultipliers, snapshot);
        } catch (IllegalArgumentException exception) {
            return null;
        }

        return plan;
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
            AelisCraftingTreeNodeBridge occurrence,
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
            if (!candidate.secondaryOutputs.isEmpty()
                    || !hasDeterministicCandidateFlags(candidate)
                    || candidate.outputPerPattern <= 0
                    || simulationFirstCandidate
                            && !hasVerifiedPatternSemantics(candidate)) {
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
            Node node, AelisCraftingTreeNodeBridge occurrence,
            boolean simulationFirstCandidate) {
        if (node == null || occurrence == null) {
            return false;
        }
        try {
            List<CraftingTreeProcess> processes =
                    occurrence.molecularmanipulator$getProcesses();
            return AelisExecutionPolicy
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
                                || !hasVerifiedPatternSemantics(candidate)
                                || !hasOnlyExactPrimaryOutputs(node, candidate)
                                || !hasDeterministicCandidateFlags(candidate)) {
                            allSimulationShapesExact = false;
                            break;
                        }
                    }
                }
                return allSimulationShapesExact
                        ? node.compiledCandidates
                        : List.of(node.compiledCandidates.get(0));
            }
            boolean allLocalShapesExact = true;
            for (int candidateIndex = 0;
                    candidateIndex < node.compiledCandidates.size();
                    candidateIndex++) {
                CompiledCandidate candidate =
                        node.compiledCandidates.get(candidateIndex);
                if (candidate.sourceIndex != candidateIndex
                        || !hasVerifiedPatternSemantics(candidate)
                        || !hasOnlyExactPrimaryOutputs(node, candidate)
                        || !hasDeterministicCandidateFlags(candidate)) {
                    allLocalShapesExact = false;
                    break;
                }
            }
            if (!AelisExecutionPolicy
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
        return List.of(node.compiledCandidates.get(0));
    }

    private static AelisSparseCapacitySolver.Candidate createSparseCapacityCandidate(
            CompiledCandidate candidate, Map<AEKey, Integer> keyIndexes,
            IdentityHashMap<GraphConsumableInput, List<InputTemplate>> consumableTemplates) {
        var inputs = new AelisSparseCapacitySolver.Input[
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
                inputs[index] = AelisSparseCapacitySolver.Input.reusable(
                        keyIndex, reusable.multiplier);
            } else {
                GraphConsumableInput consumable = orderedInput.consumableInput;
                if (consumable.substituteInput) {
                    List<InputTemplate> templates = consumableTemplates.get(consumable);
                    if (templates == null) {
                        return null;
                    }
                    var modelTemplates = new AelisSparseCapacitySolver.Template[
                            templates.size()];
                    for (int templateIndex = 0;
                            templateIndex < templates.size(); templateIndex++) {
                        InputTemplate template = templates.get(templateIndex);
                        Integer keyIndex = keyIndexes.get(template.key());
                        if (keyIndex == null) {
                            return null;
                        }
                        modelTemplates[templateIndex] =
                                new AelisSparseCapacitySolver.Template(
                                        keyIndex, template.amount());
                    }
                    inputs[index] = AelisSparseCapacitySolver.Input.substitutable(
                            consumable.childIndex, consumable.multiplier,
                            modelTemplates);
                } else {
                    inputs[index] = AelisSparseCapacitySolver.Input.consumable(
                            consumable.childIndex, consumable.multiplier);
                }
            }
        }
        // Known deterministic pattern classes are fully described by the
        // compiled output and ordered sparse inputs. Duplicate encoded patterns
        // of the same class therefore have identical capacity and must not
        // trigger another binary search through the same descendant graph.
        return new AelisSparseCapacitySolver.Candidate(
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
                (AelisCraftingTreeNodeBridge) node.occurrences.get(0),
                inventory, pauseCheckpoint);
    }

    private static boolean validateSparseConsumableTemplates(Node node,
            AelisCraftingTreeNodeBridge child, CraftingSimulationState inventory,
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
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleSnapshot)
            throws InterruptedException {
        var candidateInventory = new ChildCraftingSimulationState(parent);
        var candidateMissing = new KeyCounter();
        try {
            executeTransactionalNode(
                    graph, nodeIndex, candidateInventory, requestMultipliers,
                    rootRequestedAmount, simulation, candidateMissing, pauseCheckpoint,
                    ProgressSink.NONE, nodeIndex, candidate, requestInput, false);
            return new CandidateAttempt(
                    CandidateAttemptStatus.APPLIED, candidateInventory, candidateMissing,
                    snapshotPossibleStates(candidateRoot));
        } catch (CraftBranchFailure failure) {
            return new CandidateAttempt(CandidateAttemptStatus.SHORTAGE, null, null, null);
        } catch (CertifiedPrefixProbeFallback fallback) {
            return new CandidateAttempt(CandidateAttemptStatus.FALLBACK, null, null, null);
        } catch (Fallback fallback) {
            return new CandidateAttempt(CandidateAttemptStatus.FALLBACK, null, null, null);
        } finally {
            restorePossibleStates(candidateRoot, possibleSnapshot);
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

    private static boolean hasLiveCandidateSet(
            Node node, CraftingTreeNode candidateRoot) {
        if (candidateRoot == null || !node.allCandidatesCompiled
                || node.compiledCandidates.isEmpty()) {
            return false;
        }
        var rootBridge = (AelisCraftingTreeNodeBridge) candidateRoot;
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
        var process = (AelisCraftingTreeProcessBridge) candidateProcess;
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
                graph, node.compiledCandidates.get(0), states);
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
                AelisSimulationFirstCandidateCertificate.Node<AEKey>>(nodeCount);
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
                    ? root.occurrences.get(0)
                    : (CraftingTreeNode) requestInput.child;
        } catch (RuntimeException exception) {
            return SimulationFirstCandidateProof.reject("root_occurrence");
        }
        CandidateOccurrenceCheck requestedCheck =
                inspectSimulationFirstCandidateOccurrence(
                        root, requestedOccurrence,
                        requestedOccurrence == root.occurrences.get(0),
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
                occurrence = node.occurrences.get(0);
            } catch (RuntimeException exception) {
                return SimulationFirstCandidateProof.reject(
                        "canonical_occurrence");
            }
            if (node.amount <= 0) {
                return SimulationFirstCandidateProof.reject("invalid_occurrence");
            }
            var occurrenceBridge = (AelisCraftingTreeNodeBridge) occurrence;
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
                        new AelisSimulationFirstCandidateCertificate.Node<>(
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
            CompiledCandidate candidate = node.compiledCandidates.get(0);
            CandidateOccurrenceCheck canonicalCheck =
                    inspectSimulationFirstCandidateOccurrence(
                            node, occurrence, true, true, true);
            if (!canonicalCheck.safe) {
                return SimulationFirstCandidateProof.reject(
                        "stale_candidate0", canonicalCheck.detail);
            }
            addRecoverableProcess(recoverableProcesses, canonicalCheck);
            if (!hasVerifiedPatternSemantics(candidate)) {
                return SimulationFirstCandidateProof.reject("unknown_pattern");
            }
            if (!hasExactCompiledOutputs(node, candidate)
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
                    AelisSimulationFirstCandidateCertificate.Input<AEKey>>(
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

                List<AelisSimulationFirstCandidateCertificate.InventoryTemplate<AEKey>>
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
                            AelisSimulationFirstCandidateCertificate.InventoryTemplate<AEKey>>(
                                    snapshot.size());
                    for (InputTemplate template : snapshot) {
                        templates.add(new AelisSimulationFirstCandidateCertificate
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
                                childOccurrence == child.occurrences.get(0),
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
                inputs.add(new AelisSimulationFirstCandidateCertificate.Input<>(
                        consumable.childIndex, child.key, child.amount,
                        consumable.multiplier,
                        !consumable.substituteInput, true, liveTemplates,
                        producedKey -> liveInput.isValid(
                                producedKey, liveLevel)));
            }
            models.set(nodeIndex,
                    new AelisSimulationFirstCandidateCertificate.Node<>(
                            node.key, node.amount, false, false,
                            true, true, candidate.outputPerPattern,
                            serviceIds.candidate(
                                    candidate.details, node.key, node.amount),
                            inputs, candidate.secondaryOutputs.stream()
                                    .map(GenericStack::what).collect(java.util.stream.Collectors.toSet())));
        }

        AelisSimulationFirstCandidateCertificate.Result<AEKey> result =
                AelisSimulationFirstCandidateCertificate.evaluate(
                        models, rootIndex);
        return result.safe()
                ? SimulationFirstCandidateProof.accept(
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
                        && occurrence == node.occurrences.get(0),
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
            var bridge = (AelisCraftingTreeNodeBridge) occurrence;
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
            CompiledCandidate candidate = node.compiledCandidates.get(0);
            if (candidate.sourceIndex != 0) {
                return CandidateOccurrenceCheck.reject(
                        candidateOccurrenceDetail(
                                node, "compiled_source_index="
                                        + candidate.sourceIndex, null));
            }
            CraftingTreeProcess liveCandidate = processes.get(0);
            var process = (AelisCraftingTreeProcessBridge) liveCandidate;
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
            if (allowStateRecovery && AelisExecutionPolicy
                    .mayRecoverSimulationCandidateState(
                            simulation,
                            hasVerifiedPatternSemantics(candidate),
                            hasExactCompiledOutputs(node, candidate),
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
                        ((AelisCraftingTreeProcessBridge) process)
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
            boolean safe, String reason, String detail,
            List<CraftingTreeProcess> recoverableProcesses) {
        private static SimulationFirstCandidateProof accept(
                List<CraftingTreeProcess> recoverableProcesses) {
            return new SimulationFirstCandidateProof(
                    true, null, null,
                    recoverableProcesses == null
                            ? List.of() : List.copyOf(recoverableProcesses));
        }

        private static SimulationFirstCandidateProof reject(String reason) {
            return reject(reason, null);
        }

        private static SimulationFirstCandidateProof reject(
                String reason, String detail) {
            return new SimulationFirstCandidateProof(
                    false, reason == null ? "unknown" : reason, detail,
                    List.of());
        }
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
        if (!AelisExecutionPolicy
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
                || !hasVerifiedPatternSemantics(candidate)
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

    private static boolean isKnownDeterministicPattern(
            IPatternDetails details) {
        try {
            return getPatternBarrierReason(details) == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Compilation admits stable third-party patterns by observed semantics.
     * Every later batch proof must use that same contract, including missing
     * simulation and recovery after a real candidate temporarily failed.
     * Flags, exact outputs, live input templates and crossfeed are still
     * checked separately by each caller.
     */
    private static boolean hasVerifiedPatternSemantics(CompiledCandidate candidate) {
        if (candidate == null || candidate.details == null) {
            return false;
        }
        if (candidate.observedPatternSemantics != null) {
            return candidate.observedPatternSemantics.matches(candidate.details);
        }
        return isKnownDeterministicPattern(candidate.details);
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

    private static boolean hasExactCompiledOutputs(Node node, CompiledCandidate candidate) {
        try {
            var layout = AelisPatternOutputs.inspect(node.key, java.util.Arrays.asList(candidate.details.getOutputs()));
            return layout.requestedOutput() == candidate.outputPerPattern
                    && layout.secondaryOutputs().equals(candidate.secondaryOutputs);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isBatchValidCandidateSubgraph(
            Graph graph, CompiledCandidate candidate, byte[] states) {
        if (!candidate.secondaryOutputs.isEmpty()
                || candidate.outputPerPattern <= 0
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
                            graph, node.compiledCandidates.get(0), states);
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
                    graph, node.compiledCandidates.get(0), states);
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
            deterministic = AelisExecutionPolicy
                    .isBinarySearchCompatibleNativeLeaf(node.barrierReason);
        } else if (node.compiledCandidates.isEmpty()
                || !hasDeterministicCandidateFlags(
                        node.compiledCandidates.get(0))
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
        long outputPerPattern = candidates.get(0).outputPerPattern;
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
        // Sparse capacity and binary mixing models currently have one output
        // per firing. Ordinary transactional batches account for all outputs.
        if (!candidate.secondaryOutputs.isEmpty()) {
            return false;
        }
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
                        node.compiledCandidates.get(0));
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
                ((AelisCraftingTreeProcessBridge) process)
                        .molecularmanipulator$isPossible()));
        return result;
    }

    private static void enableSimulationCandidateRecovery(
            SimulationFirstCandidateProof proof,
            IdentityHashMap<CraftingTreeProcess, Boolean> snapshot) {
        if (proof == null || !proof.safe
                || proof.recoverableProcesses.isEmpty()) {
            return;
        }
        for (CraftingTreeProcess process : proof.recoverableProcesses) {
            if (process == null) {
                continue;
            }
            var bridge = (AelisCraftingTreeProcessBridge) process;
            boolean previous = bridge.molecularmanipulator$isPossible();
            snapshot.putIfAbsent(process, previous);
            if (!previous) {
                bridge.molecularmanipulator$setPossible(true);
            }
        }
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
            ((AelisCraftingTreeProcessBridge) process)
                    .molecularmanipulator$setPossible(previous == null || previous);
        });
        // Recovery proofs may reference a structurally equivalent occurrence
        // outside the canonical root walk. Restore every captured identity as
        // well so speculative state can never escape its transaction.
        for (var entry : snapshot.entrySet()) {
            ((AelisCraftingTreeProcessBridge) entry.getKey())
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
            var nodeBridge = (AelisCraftingTreeNodeBridge) treeNode;
            var processes = nodeBridge.molecularmanipulator$getProcesses();
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

    private static void executeNativeBoundary(Node node,
            CraftingSimulationState inventory, long requestedAmount)
            throws CraftBranchFailure, InterruptedException, Fallback {
        executeNativeBoundary(node, inventory, requestedAmount, null);
    }

    private static void executeNativeBoundary(Node node,
            CraftingSimulationState inventory, long requestedAmount,
            GraphConsumableInput requestInput)
            throws CraftBranchFailure, InterruptedException, Fallback {
        if (!AelisExecutionPolicy.mayExecuteNativeBoundary(
                node.amount, requestedAmount, MAX_LINEAR_NATIVE_BOUNDARY_ITEMS)) {
            throw new Fallback("native_boundary_work_limit:" + node.barrierReason);
        }
        var bridge = requestInput == null
                ? (AelisCraftingTreeNodeBridge) node.occurrences.get(0)
                : requestInput.child;
        bridge.molecularmanipulator$request(inventory, requestedAmount, null);
    }

    private static boolean tryExecuteReusableContainerBoundary(Node node,
            CraftingSimulationState parent, long requestedAmount,
            PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        try {
            return tryExecuteReusableContainerBoundaryUnchecked(
                    node, parent, requestedAmount, pauseCheckpoint);
        } catch (NoSuchElementException exception) {
            return false;
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
            return false;
        }

        var nodeBridge = (AelisCraftingTreeNodeBridge) node.occurrences.get(0);
        List<CraftingTreeProcess> processes = nodeBridge.molecularmanipulator$getProcesses();
        if (processes == null || processes.size() != 1) {
            return false;
        }
        if (nodeBridge.molecularmanipulator$canEmit()) {
            return false;
        }

        var process = (AelisCraftingTreeProcessBridge) processes.get(0);
        if (!process.molecularmanipulator$hasContainerItems()) {
            return false;
        }

        IPatternDetails details = process.molecularmanipulator$getDetails();
        if (details == null) {
            return false;
        }
        IPatternDetails.IInput[] inputs = details.getInputs();
        Map<CraftingTreeNode, Long> childNodes = process.molecularmanipulator$getChildNodes();
        if (inputs == null || childNodes == null || inputs.length != childNodes.size()) {
            return false;
        }
        long outputPerPattern = 0;
        for (var output : details.getOutputs()) {
            if (output == null || output.what() == null || output.amount() <= 0) {
                return false;
            }
            if (!node.key.equals(output.what())) {
                return false;
            }
            outputPerPattern = checkedAdd(outputPerPattern, output.amount(), "reusable_output_per_pattern_overflow");
        }
        if (outputPerPattern <= 0) {
            return false;
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
            return false;
        }
        long totalRequestedItems = checkedMultiply(node.amount, remainingAmount, "reusable_request_overflow");
        long patternTimes = ceilDiv(totalRequestedItems, outputPerPattern);
        var inputPlans = new ArrayList<BoundaryInputPlan>(inputs.length);
        boolean fuzzyCraftedBoundary = "fuzzy_crafted_input".equals(node.barrierReason);
        int selectedSubstituteInputs = 0;
        int inputIndex = 0;
        for (var entry : childNodes.entrySet()) {
            checkpoint(pauseCheckpoint);
            CraftingTreeNode child = entry.getKey();
            var childBridge = (AelisCraftingTreeNodeBridge) child;
            IPatternDetails.IInput input = inputs[inputIndex++];
            if (!sameInputSemantics(
                    childBridge.molecularmanipulator$getParentInput(), input)) {
                return false;
            }
            if (!input.isValid(
                    childBridge.molecularmanipulator$getWhat(),
                    childBridge.molecularmanipulator$getLevel())) {
                return false;
            }
            if (node.key.equals(childBridge.molecularmanipulator$getWhat())) {
                return false;
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
                return false;
            }
            BoundaryInputMode mode = classification.mode();

            long multiplier = input.getMultiplier();
            if (multiplier <= 0 || entry.getValue() == null || entry.getValue() != multiplier) {
                return false;
            }
            if (selectedSubstitute) {
                selectedSubstituteInputs++;
                if (!AelisExecutionPolicy
                        .mayBatchDeterministicDamageSubstitute(
                                node.barrierReason,
                                mode == BoundaryInputMode.DETERMINISTIC_DAMAGE,
                                multiplier,
                                childBridge.molecularmanipulator$getAmount())) {
                    return false;
                }
            }
            if (mode == BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                if (multiplier <= 0) {
                    return false;
                }
            }
            long childRequest = switch (mode) {
                case INVARIANT_REUSABLE -> multiplier;
                case CONSUMABLE -> checkedMultiply(multiplier, patternTimes, "reusable_consumable_request_overflow");
                case DETERMINISTIC_DAMAGE -> 0;
                case UNSAFE -> throw new IllegalStateException(
                        "Unsafe reusable boundary input escaped classification");
            };
            inputPlans.add(new BoundaryInputPlan(
                    input, childBridge, mode, childRequest, multiplier));
        }
        if (fuzzyCraftedBoundary && selectedSubstituteInputs != 1) {
            return false;
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
                    return false;
                }
            }
        }
        for (BoundaryInputPlan inputPlan : inputPlans) {
            if (inputPlan.mode != BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                if (inputPlan.mode == BoundaryInputMode.INVARIANT_REUSABLE) {
                    // AE2 records both missing and returned container items in
                    // the same counter, so prove the batch can actually start
                    // before treating its contents as returns. Otherwise a
                    // network without any catalyst would be planned as if the
                    // recipe handed one back.
                    if (!catalystAvailableAtRoot(
                            attempt, inputPlan.child, inputPlan.requestedAmount,
                            pauseCheckpoint)) {
                        return false;
                    }
                    var returnedByInput = new KeyCounter();
                    if (reusableInputAvailable(
                            attempt, inputPlan.child,
                            inputPlan.requestedAmount, pauseCheckpoint)) {
                        inputPlan.child.molecularmanipulator$request(
                                attempt, inputPlan.requestedAmount, returnedByInput);
                        for (var stack : returnedByInput) {
                            long mergedAmount = checkedAdd(
                                    containerItems.get(stack.getKey()),
                                    stack.getLongValue(),
                                    "reusable_return_count_overflow");
                            containerItems.set(stack.getKey(), mergedAmount);
                        }
                    }
                    try {
                        long additionalInputUses =
                                AelisByteAccounting.additionalRepeatedVolume(
                                        inputPlan.multiplier, patternTimes);
                        if (additionalInputUses > 0) {
                            attempt.addStackBytes(
                                    inputPlan.child.molecularmanipulator$getWhat(),
                                    inputPlan.child.molecularmanipulator$getAmount(),
                                    additionalInputUses);
                        }
                        for (var stack : returnedByInput) {
                            long additionalReturns =
                                    AelisByteAccounting.additionalRepeatedVolume(
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
                    if (!AelisExecutionPolicy.mayExecuteNativeBoundary(
                            inputPlan.child.molecularmanipulator$getAmount(),
                            inputPlan.requestedAmount,
                            MAX_LINEAR_NATIVE_BOUNDARY_ITEMS)
                            && !canSatisfyConsumableBoundaryInputFromStock(
                                    attempt, inputPlan, pauseCheckpoint)) {
                        return false;
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
            attempt.insert(output.what(), checkedMultiply(output.amount(), patternTimes, "reusable_output_overflow"),
                    Actionable.MODULATE);
        }
        attempt.addCrafting(details, patternTimes);
        ExactCraftingBytes.addBytes(attempt, patternTimes);

        long produced = attempt.extract(node.key, totalRequestedItems, Actionable.MODULATE);
        if (produced != totalRequestedItems) {
            return false;
        }
        attempt.applyDiff(parent);
        return true;
    }

    /**
     * Proves that the requested catalyst amount is really available in the
     * single-item states the boundary classified as invariant reusable.
     */
    private static boolean reusableInputAvailable(
            CraftingSimulationState inventory, AelisCraftingTreeNodeBridge child,
            long requestedAmount, PauseCheckpoint pauseCheckpoint)
            throws InterruptedException {
        if (requestedAmount <= 0 || child.molecularmanipulator$getAmount() != 1) {
            return false;
        }
        long remaining = requestedAmount;
        var selected = new KeyCounter();
        for (InputTemplate template
                : child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
            if (remaining == 0) {
                return true;
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
            selected.add(template.key(), selectedAmount);
            remaining -= selectedAmount;
        }
        return remaining == 0;
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

    private static BoundaryInputClassification classifyBoundaryInput(IPatternDetails.IInput input,
            AelisCraftingTreeNodeBridge child, CraftingSimulationState inventory,
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
    /**
     * Reserves the deterministic-damage tools a node needs for
     * {@code patternTimes} executions.
     *
     * <p>Patterns with a deterministic-damage input stay part of the aggregated
     * graph so their consumable children are planned by AELIS instead of AE2's
     * per-item native loop; the tool side is batched here, exactly like the
     * reusable-boundary path does for the same pattern shape.</p>
     */
    private static void allocateDeterministicDamageInputs(Node node,
            CraftingSimulationState inventory, long patternTimes,
            PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        for (GraphReusableInput reusable : node.reusableInputs) {
            if (reusable.mode != BoundaryInputMode.DETERMINISTIC_DAMAGE) {
                continue;
            }
            if (!allocateDeterministicDamageInput(
                    inventory, reusable.input, reusable.child,
                    reusable.multiplier, patternTimes, pauseCheckpoint)) {
                throw new Fallback("deterministic_damage_input_unavailable");
            }
        }
    }

    private static boolean allocateDeterministicDamageInput(
            CraftingSimulationState inventory, IPatternDetails.IInput input,
            AelisCraftingTreeNodeBridge child, long inputMultiplier,
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
            logicalUses = AelisByteAccounting.totalLogicalVolume(
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

        return true;
    }

    private static boolean allocateDeterministicDamageInputLegacy(
            CraftingSimulationState inventory, IPatternDetails.IInput input,
            AelisCraftingTreeNodeBridge child, long inputMultiplier,
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
        return leaseInvariantReusableInput(inventory, reusableInput,
                BigInteger.valueOf(patternTimes), returned, pauseCheckpoint);
    }

    private static boolean leaseInvariantReusableInput(
            CraftingSimulationState inventory, GraphReusableInput reusableInput,
            BigInteger patternTimes, KeyCounter returned,
            PauseCheckpoint pauseCheckpoint)
            throws CraftBranchFailure, InterruptedException, Fallback {
        if (reusableInput.multiplier <= 0
                || patternTimes.signum() <= 0
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
        if (freshlyRequested > 0
                && catalystAvailableAtRoot(
                        inventory, reusableInput.child, freshlyRequested,
                        pauseCheckpoint)) {
            // A proven self-returning catalyst only has to exist once. Nested
            // leases of one batch can hold it in flight inside the simulated
            // child states, and asking AE2 for it again would make
            // CraftingTreeNode.request record a phantom missing entry (or, in
            // the real attempt, throw CraftBranchFailure) for a catalyst the
            // network still owns.
            freshlyRequested = 0;
        }
        if (freshlyRequested > 0) {
            if (!AelisExecutionPolicy.mayExecuteNativeBoundary(
                    reusableInput.child.molecularmanipulator$getAmount(),
                    freshlyRequested,
                    MAX_LINEAR_NATIVE_BOUNDARY_ITEMS)) {
                return false;
            }
            reusableInput.child.molecularmanipulator$request(
                    inventory, freshlyRequested, returned);
        }

        BigInteger logicalUses = AelisBigIntegerMath.multiply(reusableInput.multiplier, patternTimes);
        var additionalLogicalUses = logicalUses.subtract(BigInteger.valueOf(freshlyRequested)).max(BigInteger.ZERO);
        if (additionalLogicalUses.signum() > 0) {
            ExactCraftingBytes.addStackBytes(inventory,
                    reusableInput.child.molecularmanipulator$getWhat(), additionalLogicalUses);
        }
        return true;
    }

    /**
     * Checks whether the whole planning batch, not just the current child
     * state, can supply {@code needed} units of a reusable catalyst. Nested
     * transactional leases hand a returning catalyst between child states, so
     * only the calculation root shows whether the network really owns it.
     */
    private static boolean catalystAvailableAtRoot(
            CraftingSimulationState inventory,
            AelisCraftingTreeNodeBridge child, long needed,
            PauseCheckpoint pauseCheckpoint) throws InterruptedException {
        if (needed <= 0) {
            return true;
        }
        ICraftingInventory root = inventory;
        while (root instanceof ChildCraftingSimulationState childState) {
            root = ((AelisChildSimulationStateAccessor) childState)
                    .appliedenhancements$getParent();
        }
        long remaining = needed;
        var selected = new KeyCounter();
        for (InputTemplate template
                : child.molecularmanipulator$getValidItemTemplates(root)) {
            checkpoint(pauseCheckpoint);
            if (remaining == 0) {
                return true;
            }
            if (template == null || template.key() == null
                    || template.amount() != 1) {
                return false;
            }
            long available = root.extract(
                    template.key(), Long.MAX_VALUE, Actionable.SIMULATE);
            available = Math.max(0, available - selected.get(template.key()));
            long selectedAmount = Math.min(remaining, available);
            if (selectedAmount == 0) {
                continue;
            }
            selected.add(template.key(), selectedAmount);
            remaining -= selectedAmount;
        }
        return remaining == 0;
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

    private static BigInteger extractBigIntegerConsumableInputTemplates(Node node,
            CraftingSimulationState inventory, GraphConsumableInput requestInput,
            BigInteger requestedMultipliers, BigInteger requestedItems,
            PauseCheckpoint pauseCheckpoint) throws Fallback, InterruptedException {
        if (requestedItems.bitLength() < 64) {
            return BigInteger.valueOf(extractConsumableInputTemplates(node, inventory,
                    requestInput, requestedMultipliers.longValueExact(),
                    requestedItems.longValueExact(), pauseCheckpoint));
        }
        if (requestInput.childIndex != node.index || requestInput.input == null
                || requestInput.child == null
                || !node.key.equals(requestInput.child.molecularmanipulator$getWhat())
                || node.amount != requestInput.child.molecularmanipulator$getAmount()) {
            throw new Fallback("invalid_substitute_input_context");
        }
        BigInteger remaining = requestedMultipliers;
        for (InputTemplate template : requestInput.child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
            if (template == null || template.key() == null || template.amount() <= 0) {
                throw new Fallback("invalid_substitute_input_template");
            }
            if (!requestInput.substituteInput) {
                if (!node.key.equals(template.key()) || template.amount() != node.amount) {
                    throw new Fallback("fuzzy_or_contextual_input");
                }
            } else if (classifyRemainingKey(requestInput.input, template.key(),
                    requestInput.child.molecularmanipulator$getLevel()) != BoundaryInputMode.CONSUMABLE) {
                throw new Fallback("unsafe_substitute_input_template");
            }
            if (com.appliedenhancements.storage.InfiniteStorageSupport.consume(
                    inventory, template.key(), remaining.multiply(BigInteger.valueOf(template.amount())))) {
                return BigInteger.ZERO;
            }
            // Finite inventory quantities stay long; only the unmet demand may be larger.
            if (requestInput.substituteInput) {
                remaining = remaining.subtract(BigInteger.valueOf(extractTemplateMultipliers(
                        inventory, template, AelisBigIntegerMath.saturatingLong(remaining))));
                if (remaining.signum() == 0) break;
            }
        }
        if (!requestInput.substituteInput) {
            if (com.appliedenhancements.storage.InfiniteStorageSupport.consume(inventory, node.key, requestedItems)) {
                return BigInteger.ZERO;
            }
            remaining = remaining.subtract(BigInteger.valueOf(extractTemplateMultipliers(
                    inventory, new InputTemplate(node.key, node.amount),
                    AelisBigIntegerMath.saturatingLong(remaining))));
        }
        return remaining;
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
        long remaining = requestedMultipliers;
        for (InputTemplate template
                : requestInput.child.molecularmanipulator$getValidItemTemplates(inventory)) {
            checkpoint(pauseCheckpoint);
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
            AelisCraftingTreeNodeBridge child, BoundaryInputMode mode,
            long requestedAmount, long multiplier) {
    }

    private record GraphReusableInput(IPatternDetails.IInput input,
            AelisCraftingTreeNodeBridge child, BoundaryInputMode mode,
            long multiplier) {
    }

    private record GraphConsumableInput(IPatternDetails.IInput input,
            AelisCraftingTreeNodeBridge child, int childIndex, long multiplier,
            boolean substituteInput, boolean quantityFeedbackInput) {
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
            var bridge = (AelisCraftingTreeNodeBridge) occurrence;
            Iterable<InputTemplate> templates = bridge.molecularmanipulator$getValidItemTemplates(inventory);
            for (InputTemplate template : templates) {
                if (!node.key.equals(template.key()) || template.amount() != node.amount) {
                    throw new Fallback("fuzzy_or_contextual_input");
                }
            }
        }
    }

    private static List<LocalCyclicRegion> assignLocalCyclicRegions(
            List<Node> nodes, ICraftingService craftingService) throws Fallback {
        var models = new LinkedHashMap<AEKey,
                AelisCyclicRegionDetector.KeyModel<AEKey, CompiledCandidate>>();
        var canonicalCandidates = new LinkedHashMap<AEKey, List<CompiledCandidate>>();
        var exactNodes = new LinkedHashMap<NodeKey, Node>();
        var terminalNodes = new LinkedHashMap<AEKey, Node>();
        for (Node node : nodes) {
            if (!node.reachable) {
                continue;
            }
            exactNodes.putIfAbsent(new NodeKey(node.key, node.amount), node);
            if (node.terminal) {
                terminalNodes.putIfAbsent(node.key, node);
            }
            if (node.compiledCandidates.isEmpty()) {
                if (!node.terminal) {
                    var existing = models.get(node.key);
                    String reason = node.emitter
                            ? "emitter occurrence"
                            : node.barrier
                                    ? "barrier:" + node.barrierReason
                                    : "producer occurrence has no compiled candidates";
                    models.put(node.key, new AelisCyclicRegionDetector.KeyModel<>(
                            existing == null ? List.of() : existing.variants(),
                            false,
                            joinCycleRejection(
                                    existing == null ? null : existing.rejectionReason(),
                                    reason)));
                }
                continue;
            }
            CycleKeyModel built = buildCycleKeyModel(nodes, node);
            var existing = models.get(node.key);
            List<CompiledCandidate> canonical = canonicalCandidates.get(node.key);
            if (existing == null) {
                models.put(node.key, built.model);
                canonicalCandidates.put(node.key, List.copyOf(node.compiledCandidates));
                continue;
            }
            boolean compatible = canonical != null
                    && sameCycleCandidateSet(
                            nodes, canonical, node.compiledCandidates);
            boolean supported = existing.supported()
                    && built.model.supported() && compatible;
            models.put(node.key, new AelisCyclicRegionDetector.KeyModel<>(
                    existing.variants().isEmpty()
                            ? built.model.variants()
                            : existing.variants(),
                    supported,
                    supported ? null : joinCycleRejection(
                            existing.rejectionReason(),
                            built.model.rejectionReason(),
                            compatible ? null : "candidate set differs across contexts")));
        }

        if (craftingService != null && !terminalNodes.isEmpty()) {
            restoreConnectedRawCycleCandidates(
                    models, exactNodes, terminalNodes, craftingService);
        }

        var analysis = AelisCyclicRegionDetector.analyze(models);
        var assignedRegions = new ArrayList<LocalCyclicRegion>();
        for (var region : analysis.regions()) {
            var local = new LocalCyclicRegion(region);
            assignedRegions.add(local);
            for (Node node : nodes) {
                if (region.keys().contains(node.key)) {
                    node.localCyclicRegion = local;
                }
            }
        }
        return List.copyOf(assignedRegions);
    }

    private static CycleKeyModel buildCycleKeyModel(List<Node> nodes, Node node)
            throws Fallback {
        var rejectionReasons = new LinkedHashSet<String>();
        boolean supported = !node.emitter && !node.barrier
                && (node.executionMode == ExecutionMode.PURE_FAST
                        || node.executionMode == ExecutionMode.HYBRID_BARRIER
                                && "ordered_pattern_choices".equals(node.barrierReason))
                && node.allCandidatesCompiled
                && node.compiledCandidates.size() == node.candidatePatterns.size();
        if (node.emitter) {
            rejectionReasons.add("emitter producer");
        }
        if (node.barrier) {
            rejectionReasons.add("barrier:" + node.barrierReason);
        }
        if (node.executionMode != ExecutionMode.PURE_FAST
                && !(node.executionMode == ExecutionMode.HYBRID_BARRIER
                        && "ordered_pattern_choices".equals(node.barrierReason))) {
            rejectionReasons.add("execution mode:" + node.executionMode
                    + ":" + node.barrierReason);
        }
        if (!node.allCandidatesCompiled
                || node.compiledCandidates.size() != node.candidatePatterns.size()) {
            rejectionReasons.add("incomplete candidate set:compiled="
                    + node.compiledCandidates.size() + ",total="
                    + node.candidatePatterns.size() + ",failures="
                    + node.candidateCompileFailures);
        }
        var variants = new ArrayList<
                AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>(
                        node.compiledCandidates.size());
        for (CompiledCandidate candidate : node.compiledCandidates) {
            if (candidate == null || candidate.outputPerPattern <= 0) {
                supported = false;
                rejectionReasons.add("invalid candidate output");
                continue;
            }
            if (!candidate.secondaryOutputs.isEmpty()) {
                supported = false;
                rejectionReasons.add("secondary outputs");
            }
            if (candidate.hasContainerItems) {
                supported = false;
                rejectionReasons.add("container items");
            }
            var inputs = new LinkedHashMap<AEKey, BigInteger>();
            for (OrderedGraphInput orderedInput : candidate.orderedInputs) {
                if (orderedInput.reusable()) {
                    supported = false;
                    rejectionReasons.add("reusable input");
                    continue;
                }
                GraphConsumableInput consumable = orderedInput.consumableInput;
                if (consumable == null || consumable.childIndex < 0
                        || consumable.childIndex >= nodes.size()) {
                    supported = false;
                    rejectionReasons.add("invalid consumable child");
                    continue;
                }
                if (consumable.substituteInput) {
                    supported = false;
                    rejectionReasons.add("substitute input");
                }
                Node child = nodes.get(consumable.childIndex);
                long amount = checkedMultiply(
                        child.amount, consumable.multiplier,
                        "local_cyclic_input_amount_overflow");
                inputs.merge(child.key, BigInteger.valueOf(amount), BigInteger::add);
            }
            var modelInputs = new ArrayList<
                    AelisCyclicDemandSolver.Input<AEKey>>(inputs.size());
            for (var input : inputs.entrySet()) {
                modelInputs.add(new AelisCyclicDemandSolver.Input<>(
                        input.getKey(), input.getValue()));
            }
            variants.add(new AelisCyclicDemandSolver.Variant<>(
                    candidate, node.key,
                    BigInteger.valueOf(candidate.outputPerPattern), modelInputs));
        }
        if (variants.size() != node.compiledCandidates.size()) {
            supported = false;
            rejectionReasons.add("candidate model incomplete");
        }
        return new CycleKeyModel(
                new AelisCyclicRegionDetector.KeyModel<>(
                        variants, supported,
                        supported ? null : String.join("; ", rejectionReasons)));
    }

    private static List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>
            buildRawCycleVariants(
                    Map<NodeKey, Node> exactNodes,
                    Map<AEKey, Node> terminalNodes,
                    AEKey outputKey, ICraftingService craftingService) {
        Node outputNode = terminalNodes.get(outputKey);
        if (outputNode == null) {
            return List.of();
        }
        return buildRawCycleVariants(
                exactNodes, outputNode.level, outputKey, craftingService);
    }

    private static List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>
            buildRawCycleVariants(
                    Map<NodeKey, Node> exactNodes,
                    net.minecraft.world.level.Level outputLevel,
                    AEKey outputKey, ICraftingService craftingService) {
        var variants = new ArrayList<
                AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>();
        int sourceIndex = 0;
        boolean allCandidatesSafe = true;
        try {
            for (IPatternDetails details : craftingService.getCraftingFor(outputKey)) {
                if (details == null) {
                    allCandidatesSafe = false;
                    sourceIndex++;
                    continue;
                }
                long outputPerPattern = 0;
                boolean outputSafe = true;
                for (GenericStack output : details.getOutputs()) {
                    if (output == null || output.what() == null || output.amount() <= 0
                            || !outputKey.equals(output.what())) {
                        outputSafe = false;
                        break;
                    }
                    outputPerPattern = Math.addExact(
                            outputPerPattern, output.amount());
                }
                if (!outputSafe || outputPerPattern <= 0) {
                    allCandidatesSafe = false;
                    sourceIndex++;
                    continue;
                }

                String barrierReason = getPatternBarrierReason(details);
                AelisObservedPatternSemantics.Snapshot observed =
                        barrierReason == null
                                ? null
                                : AelisObservedPatternSemantics.captureStable(details);
                if (barrierReason != null && observed == null) {
                    allCandidatesSafe = false;
                    sourceIndex++;
                    continue;
                }

                var orderedInputs = new ArrayList<OrderedGraphInput>();
                var edges = new ArrayList<Edge>();
                var rawInputs = new ArrayList<RawCycleInput>();
                var modelInputAmounts = new LinkedHashMap<AEKey, BigInteger>();
                boolean inputSafe = true;
                for (IPatternDetails.IInput input : details.getInputs()) {
                    GenericStack exact = getSingleExactInputChoice(input);
                    if (exact == null || exact.what() == null || exact.amount() <= 0
                            || input.getMultiplier() <= 0
                            || !input.isValid(exact.what(), outputLevel)
                            || input.getRemainingKey(exact.what()) != null) {
                        inputSafe = false;
                        break;
                    }
                    long multiplier = input.getMultiplier();
                    BigInteger totalAmount = BigInteger.valueOf(exact.amount())
                            .multiply(BigInteger.valueOf(multiplier));
                    modelInputAmounts.merge(
                            exact.what(), totalAmount, BigInteger::add);

                    Node child = exactNodes.get(
                            new NodeKey(exact.what(), exact.amount()));
                    int boundChildIndex = -1;
                    if (child != null && !child.occurrences.isEmpty()) {
                        var childBridge = (AelisCraftingTreeNodeBridge)
                                child.occurrences.get(0);
                        var consumable = new GraphConsumableInput(
                                input, childBridge, child.index,
                                multiplier, false, false);
                        orderedInputs.add(OrderedGraphInput.consumable(consumable));
                        edges.add(new Edge(child.index, multiplier, 1));
                        boundChildIndex = child.index;
                    }
                    rawInputs.add(new RawCycleInput(
                            input, exact.what(), exact.amount(), multiplier,
                            boundChildIndex, outputLevel));
                }
                if (!inputSafe) {
                    allCandidatesSafe = false;
                    sourceIndex++;
                    continue;
                }

                var modelInputs = new ArrayList<
                        AelisCyclicDemandSolver.Input<AEKey>>(modelInputAmounts.size());
                for (var input : modelInputAmounts.entrySet()) {
                    modelInputs.add(new AelisCyclicDemandSolver.Input<>(
                            input.getKey(), input.getValue()));
                }
                var candidate = new CompiledCandidate(
                        sourceIndex, null, details,
                        List.copyOf(orderedInputs), List.copyOf(edges),
                        outputPerPattern, false,
                        hasPatternOutputAsInput(details), false,
                        observed, null, List.copyOf(rawInputs));
                variants.add(new AelisCyclicDemandSolver.Variant<>(
                        candidate, outputKey,
                        BigInteger.valueOf(outputPerPattern), modelInputs));
                sourceIndex++;
            }
        } catch (RuntimeException exception) {
            return List.of();
        }
        return allCandidatesSafe ? List.copyOf(variants) : List.of();
    }

    /**
     * Restores recursion-hidden candidates and follows only dependencies that
     * can reach an already known producer. This closes a hidden cyclic region
     * through arbitrarily many layers without expanding unrelated recipes for
     * ordinary external materials such as sand or metal.
     */
    private static void restoreConnectedRawCycleCandidates(
            Map<AEKey, AelisCyclicRegionDetector.KeyModel<AEKey, CompiledCandidate>> models,
            Map<NodeKey, Node> exactNodes,
            Map<AEKey, Node> terminalNodes,
            ICraftingService craftingService) {
        var initial = AelisCyclicPatternOverlay.merge(
                models, terminalNodes.keySet(),
                key -> buildRawCycleVariants(
                        exactNodes, terminalNodes, key, craftingService));
        models.clear();
        models.putAll(initial.models());

        Set<AEKey> restored = initial.restoredKeys();
        var knownProducers = new LinkedHashSet<AEKey>();
        for (var entry : models.entrySet()) {
            if (!entry.getValue().variants().isEmpty()) {
                knownProducers.add(entry.getKey());
            }
        }

        var pending = new ArrayDeque<AEKey>(restored);
        var cachedCandidates = new LinkedHashMap<AEKey,
                List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>>();
        for (AEKey key : restored) {
            var model = models.get(key);
            if (model != null) {
                cachedCandidates.put(key, model.variants());
            }
        }

        while (!pending.isEmpty()) {
            AEKey owner = pending.removeFirst();
            var ownerModel = models.get(owner);
            if (ownerModel == null) {
                continue;
            }
            for (var variant : ownerModel.variants()) {
                CompiledCandidate candidate = variant.id();
                for (RawCycleInput rawInput : candidate.rawCycleInputs) {
                    if (rawInput.childIndex >= 0
                            || knownProducers.contains(rawInput.key)) {
                        continue;
                    }
                    var expansion = findConnectedRawCycleExpansion(
                            rawInput.key, rawInput.level, knownProducers,
                            cachedCandidates, new HashSet<>(), exactNodes,
                            craftingService);
                    if (!expansion.connected()) {
                        continue;
                    }
                    for (var discovered : expansion.expansions()) {
                        if (!knownProducers.add(discovered.key())) {
                            continue;
                        }
                        models.put(discovered.key(),
                                new AelisCyclicRegionDetector.KeyModel<>(
                                        discovered.variants(), true));
                        pending.addLast(discovered.key());
                    }
                }
            }
        }
    }

    private static RawCycleSearch findConnectedRawCycleExpansion(
            AEKey key, net.minecraft.world.level.Level level,
            Set<AEKey> knownProducers,
            Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>> cache,
            Set<AEKey> visiting,
            Map<NodeKey, Node> exactNodes,
            ICraftingService craftingService) {
        if (knownProducers.contains(key)) {
            return new RawCycleSearch(true, List.of());
        }
        if (!visiting.add(key)) {
            return new RawCycleSearch(true, List.of());
        }
        try {
            var variants = cache.computeIfAbsent(key,
                    ignored -> buildRawCycleVariants(
                            exactNodes, level, key, craftingService));
            if (variants.isEmpty()) {
                return new RawCycleSearch(false, List.of());
            }

            var discovered = new LinkedHashMap<AEKey, RawCycleExpansion>();
            boolean connected = false;
            for (var variant : variants) {
                CompiledCandidate candidate = variant.id();
                for (RawCycleInput rawInput : candidate.rawCycleInputs) {
                    if (rawInput.childIndex >= 0) {
                        if (knownProducers.contains(rawInput.key)) {
                            connected = true;
                        }
                        continue;
                    }
                    if (knownProducers.contains(rawInput.key)) {
                        connected = true;
                        continue;
                    }
                    var nested = findConnectedRawCycleExpansion(
                            rawInput.key, rawInput.level, knownProducers,
                            cache, visiting, exactNodes, craftingService);
                    if (nested.connected()) {
                        connected = true;
                        for (var entry : nested.expansions()) {
                            discovered.putIfAbsent(entry.key(), entry);
                        }
                    }
                }
            }
            if (!connected) {
                return new RawCycleSearch(false, List.of());
            }
            var result = new ArrayList<RawCycleExpansion>(discovered.size() + 1);
            result.add(new RawCycleExpansion(key, variants));
            result.addAll(discovered.values());
            return new RawCycleSearch(true, result);
        } finally {
            visiting.remove(key);
        }
    }

    private static String joinCycleRejection(String... reasons) {
        var result = new LinkedHashSet<String>();
        for (String reason : reasons) {
            if (reason != null && !reason.isBlank()) {
                result.add(reason);
            }
        }
        return result.isEmpty()
                ? "producer is outside exact cyclic semantics"
                : String.join("; ", result);
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
        private final ICraftingService craftingService;
        private long mergedOccurrences;
        private long pausedNanos;
        private int orderedChoiceCount;
        private boolean hasSubstituteInputs;
        private boolean hasReusableInputs;

        private Compiler(int maxNodes, long deadline, PauseCheckpoint pauseCheckpoint,
                Set<AEKey> contextSplitKeys, ProgressSink progressSink,
                ICraftingService craftingService) {
            this.maxNodes = maxNodes;
            this.deadline = deadline;
            this.pauseCheckpoint = pauseCheckpoint;
            this.contextSplitKeys = contextSplitKeys;
            this.progressSink = progressSink;
            this.craftingService = craftingService;
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
                        throw new Fallback("unsafe_pattern_boundary:" + barrier.reason);
                    }
                }
            }

            validateReusableGraphConflicts();
            validateQuantityFeedbackIsolation();

            int[] topologicalOrder = buildTopologicalOrder();
            long logicalNodeCount = countLogicalNodes(rootIndex, topologicalOrder);
            int barrierCount = 0;

            // The single AELIS policy keeps compatible nodes in the fast
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
            var cyclicRegions = assignLocalCyclicRegions(nodes, craftingService);
            var graph = new Graph(List.copyOf(nodes), topologicalOrder, rootIndex,
                    logicalNodeCount, mergedOccurrences, barrierCount, orderedChoiceCount,
                    !contextSplitKeys.isEmpty()
                            || !crossAmountContextSensitiveKeys.isEmpty(),
                    hasSubstituteInputs, hasReusableInputs, craftingService, cyclicRegions);
            prepareGlobalCyclicModel(graph);
            checkBudget();
            return graph;
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
                            for (CompiledCandidate candidate : graphNode.compiledCandidates) {
                                for (GenericStack output : candidate.secondaryOutputs) {
                                    if (reusableInput.input.isValid(output.what(),
                                            reusableInput.child.molecularmanipulator$getLevel())) {
                                        throw new Fallback("reusable_candidate_graph_conflict");
                                    }
                                }
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
                if (!AelisQuantityFeedbackBatch.isSafeDescendantInput(
                        new AelisQuantityFeedbackBatch.DescendantInputShape(
                                false, consumable.substituteInput, false))) {
                    return false;
                }
                Integer existingOccurrence = descendantOccurrences.putIfAbsent(
                        child.key, child.index);
                if (!AelisQuantityFeedbackBatch.isCompatibleDescendantOccurrence(
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
                if (!AelisQuantityFeedbackBatch.isSafeDescendantNode(
                        new AelisQuantityFeedbackBatch.DescendantNodeShape(
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
                    if (!descendantCandidate.secondaryOutputs.isEmpty()) {
                        return false;
                    }
                    if (!AelisQuantityFeedbackBatch.isSafeDescendantCandidate(
                            new AelisQuantityFeedbackBatch.DescendantCandidateShape(
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
                        if (!AelisQuantityFeedbackBatch.isSafeDescendantInput(
                                new AelisQuantityFeedbackBatch.DescendantInputShape(
                                        input.reusable(),
                                        !input.reusable() && consumable.substituteInput,
                                        child != null && child.key.equals(owner.key)))) {
                            return false;
                        }
                        Integer existingOccurrence = descendantOccurrences.putIfAbsent(
                                child.key, child.index);
                        if (!AelisQuantityFeedbackBatch.isCompatibleDescendantOccurrence(
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
            var bridge = (AelisCraftingTreeNodeBridge) occurrence;
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
            var nodeBridge = (AelisCraftingTreeNodeBridge) occurrence;
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
                    node, processes.get(0), context, 0, true));
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
                        break;
                    }
                }
            }
        }

        private CompiledCandidate compileCandidate(Node node,
                CraftingTreeProcess candidateProcess, RecipeContext context,
                int sourceIndex, boolean primary)
                throws Fallback, Barrier, ContextSplit, InterruptedException {
            var process = (AelisCraftingTreeProcessBridge) candidateProcess;
            boolean hasContainerItems = process.molecularmanipulator$hasContainerItems();
            boolean limitsQuantity = process.molecularmanipulator$limitsQuantity();
            boolean quantityFeedbackCandidate = limitsQuantity && !hasContainerItems;

            IPatternDetails details = process.molecularmanipulator$getDetails();
            String patternBarrierReason = getPatternBarrierReason(details);
            AelisObservedPatternSemantics.Snapshot observedPatternSemantics =
                    patternBarrierReason != null
                            ? AelisObservedPatternSemantics.captureStable(details)
                            : null;
            boolean structurallyStablePattern =
                    observedPatternSemantics != null;
            if (patternBarrierReason != null
                    && !structurallyStablePattern) {
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

            AelisPatternOutputs.Layout outputLayout;
            GenericStack[] outputStacks = details.getOutputs();
            List<GenericStack> outputs = outputStacks == null
                    ? null : java.util.Arrays.asList(outputStacks);
            try {
                outputLayout = AelisPatternOutputs.inspect(node.key, outputs);
            } catch (IllegalArgumentException exception) {
                throw new Barrier(exception.getMessage());
            } catch (ArithmeticException exception) {
                throw new Barrier("output_count_overflow");
            }
            long outputPerPattern = outputLayout.requestedOutput();
            int outputEntryCount = outputs.size();
            if (!outputLayout.secondaryOutputs().isEmpty()
                    && (hasContainerItems || limitsQuantity
                            || patternBarrierReason != null && !structurallyStablePattern)) {
                throw new Barrier("stateful_multi_output");
            }

            IPatternDetails.IInput[] inputs = details.getInputs();
            Map<CraftingTreeNode, Long> childNodes = process.molecularmanipulator$getChildNodes();
            if (inputs == null || childNodes == null || inputs.length != childNodes.size()) {
                throw new Barrier("dynamic_input_layout");
            }

            var validatedInputs = new ArrayList<ValidatedOccurrenceInput>(inputs.length);
            var quantityInputShapes = quantityFeedbackCandidate
                    ? new ArrayList<AelisQuantityFeedbackBatch.InputShape>(inputs.length)
                    : null;
            boolean hasReusableInput = false;
            boolean candidateHasSubstituteInputs = false;
            boolean feedbackOccurrenceTerminal = true;
            int inputIndex = 0;
            for (var entry : childNodes.entrySet()) {
                checkBudget();
                CraftingTreeNode child = entry.getKey();
                var childBridge = (AelisCraftingTreeNodeBridge) child;
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
                    // Both INVARIANT_REUSABLE and DETERMINISTIC_DAMAGE inputs stay in
                    // the graph. Deterministic damage variants are batched by
                    // allocateDeterministicDamageInput during execution, and the
                    // single-unit selected input with a positive multiplier checked
                    // above is exactly the shape that batcher accepts. Keeping the
                    // pattern inside the graph is what lets its consumable children be
                    // aggregated instead of forcing a native linear boundary.
                    hasReusableInput = true;
                }
                boolean quantityFeedbackInput = quantityFeedbackCandidate
                        && node.key.equals(selectedKey);
                if (quantityFeedbackInput) {
                    feedbackOccurrenceTerminal &=
                            isTerminalQuantityFeedbackOccurrence(childBridge);
                }
                if (quantityInputShapes != null) {
                    quantityInputShapes.add(new AelisQuantityFeedbackBatch.InputShape(
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
            AelisQuantityFeedbackBatch.Profile quantityFeedbackProfile = null;
            if (quantityFeedbackCandidate) {
                var quantityShape = new AelisQuantityFeedbackBatch.Shape(
                        node.amount, node.candidatePatterns.size(),
                        hasContainerItems,
                        patternBarrierReason == null
                                || structurallyStablePattern,
                        true, feedbackOccurrenceTerminal, outputEntryCount,
                        outputPerPattern, quantityInputShapes);
                quantityFeedbackProfile =
                        AelisQuantityFeedbackBatch.profile(quantityShape);
                if (quantityFeedbackProfile == null) {
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
            if (patternOwner != null && !patternOwner.equals(patternNodeKey)
                    && outputLayout.secondaryOutputs().isEmpty()) {
                throw new Barrier("shared_pattern_with_different_request_units");
            }

            var orderedInputs = new ArrayList<OrderedGraphInput>(inputs.length);
            var accumulators = new LinkedHashMap<Integer, EdgeAccumulator>();
            RecipeContext childContext = context.extend(node.key);
            for (ValidatedOccurrenceInput validatedInput : validatedInputs) {
                var childBridge = (AelisCraftingTreeNodeBridge) validatedInput.child;
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
                        validatedInput.multiplier, validatedInput.substituteInput,
                        validatedInput.quantityFeedbackInput);
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
                    hasContainerItems, limitsQuantity, quantityFeedbackBatch,
                    structurallyStablePattern ? observedPatternSemantics : null,
                    quantityFeedbackProfile, List.of(), outputLayout.secondaryOutputs());
            hasSubstituteInputs |= candidateHasSubstituteInputs;
            hasReusableInputs |= hasReusableInput;
            if (primary) {
                node.details = details;
                node.observedPatternSemantics =
                        structurallyStablePattern
                                ? observedPatternSemantics
                                : null;
                node.hasContainerItems = hasContainerItems;
                node.limitsQuantity = limitsQuantity;
                node.outputPerPattern = outputPerPattern;
                node.secondaryOutputs = outputLayout.secondaryOutputs();
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
                AelisCraftingTreeNodeBridge child) {
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
            var nodeBridge = (AelisCraftingTreeNodeBridge) occurrence;
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
                    throw createContextSplit(node, context, "contextual_terminal");
                }
                return;
            }
            if (node.terminal) {
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
                var bridge = (AelisCraftingTreeProcessBridge) candidate;
                CandidateProgressCheck progress = checkDirectCandidateProgress(
                        node, bridge);
                if (progress.noProgress()) {
                    bridge.molecularmanipulator$setPossible(false);
                    if (canonical) {
                        node.noProgressPatterns.put(
                                bridge.molecularmanipulator$getDetails(),
                                Boolean.TRUE);
                    }
                    continue;
                }
                result.add(candidate);
            }
            return List.copyOf(result);
        }

        private CandidateProgressCheck checkDirectCandidateProgress(
                Node node, AelisCraftingTreeProcessBridge process) {
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
                    var child = (AelisCraftingTreeNodeBridge) entry.getKey();
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
                        ? CandidateProgressCheck.NO_PROGRESS
                        : CandidateProgressCheck.PROGRESS;
            } catch (RuntimeException exception) {
                return CandidateProgressCheck.PROGRESS;
            }
        }

        private void validateCandidateOccurrence(Node node,
                CraftingTreeProcess candidateProcess, CompiledCandidate candidate,
                RecipeContext context)
                throws Fallback, ContextSplit, InterruptedException {
            var process = (AelisCraftingTreeProcessBridge) candidateProcess;
            boolean observedSemanticsChanged =
                    candidate.observedPatternSemantics != null
                            && !candidate.observedPatternSemantics.matches(
                                    process.molecularmanipulator$getDetails());
            if (observedSemanticsChanged
                    || !samePatternSemantics(
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
                var childBridge = (AelisCraftingTreeNodeBridge) child;
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
            crossAmountContextSensitiveKeys.add(node.key);
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
                addContextKeys(keys, node.occurrenceContexts.get(0));
            }
            addContextKeys(keys, context);
            return new ContextSplit(reason, node.key, Set.copyOf(keys));
        }

        private void addContextKeys(Set<AEKey> keys, RecipeContext context) {
            for (RecipeContext cursor = context; cursor.depth > 0; cursor = cursor.parent) {
                keys.add(cursor.key);
            }
        }

        private List<IPatternDetails> getCandidatePatterns(
                List<CraftingTreeProcess> processes) throws Fallback {
            var result = new ArrayList<IPatternDetails>(processes.size());
            for (CraftingTreeProcess candidate : processes) {
                var candidateBridge = (AelisCraftingTreeProcessBridge) candidate;
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
        // input/output semantics. Support it to enable AELIS for creative-tier
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

    private static void validateObservedPatternSemantics(
            Node node, CompiledCandidate compiledCandidate)
            throws Fallback {
        AelisObservedPatternSemantics.Snapshot snapshot = compiledCandidate == null
                ? node.observedPatternSemantics
                : compiledCandidate.observedPatternSemantics;
        IPatternDetails details = compiledCandidate == null
                ? node.details
                : compiledCandidate.details;
        if (snapshot != null && !snapshot.matches(details)) {
            throw new Fallback("observed_pattern_semantics_changed");
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
            IdentityHashMap<CraftingTreeProcess, Boolean> possibleStates) {
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
        private AelisObservedPatternSemantics.Snapshot observedPatternSemantics;
        private List<IPatternDetails> candidatePatterns = List.of();
        private boolean hasContainerItems;
        private boolean limitsQuantity;
        private long outputPerPattern;
        private List<GenericStack> secondaryOutputs = List.of();
        private long logicalOccurrences;
        private boolean allCandidatesCompiled;
        private LocalCyclicRegion localCyclicRegion;
        private Boolean deterministicCandidateSubgraph;
        private Boolean batchValidFirstCandidateSubgraph;
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
        HYBRID_BARRIER    // Upstream batch + this node calls AE2
    }

    private record Edge(int childIndex, long requestMultiplier, int occurrences) {
    }

    private record CompiledCandidate(int sourceIndex,
            CraftingTreeProcess sourceProcess, IPatternDetails details,
            List<OrderedGraphInput> orderedInputs, List<Edge> edges,
            long outputPerPattern, boolean hasContainerItems,
            boolean limitsQuantity, boolean quantityFeedbackBatch,
            AelisObservedPatternSemantics.Snapshot observedPatternSemantics,
            AelisQuantityFeedbackBatch.Profile quantityFeedbackProfile,
            List<RawCycleInput> rawCycleInputs, List<GenericStack> secondaryOutputs) {
        private CompiledCandidate {
            secondaryOutputs = List.copyOf(secondaryOutputs);
        }

        private CompiledCandidate(int sourceIndex,
                CraftingTreeProcess sourceProcess, IPatternDetails details,
                List<OrderedGraphInput> orderedInputs, List<Edge> edges,
                long outputPerPattern, boolean hasContainerItems,
                boolean limitsQuantity, boolean quantityFeedbackBatch,
                AelisObservedPatternSemantics.Snapshot observedPatternSemantics,
                AelisQuantityFeedbackBatch.Profile quantityFeedbackProfile,
                List<RawCycleInput> rawCycleInputs) {
            this(sourceIndex, sourceProcess, details, orderedInputs, edges,
                    outputPerPattern, hasContainerItems, limitsQuantity,
                    quantityFeedbackBatch, observedPatternSemantics,
                    quantityFeedbackProfile, rawCycleInputs, List.of());
        }
    }

    private record RawCycleInput(
            IPatternDetails.IInput input, AEKey key,
            long amount, long multiplier, int childIndex,
            net.minecraft.world.level.Level level) {
    }

    private record RawCycleExpansion(
            AEKey key,
            List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>> variants) {
    }

    private record RawCycleSearch(
            boolean connected, List<RawCycleExpansion> expansions) {
    }

    private record LocalCyclicRegion(
            AelisCyclicRegionDetector.Region<AEKey, CompiledCandidate> model) {
    }

    private record CycleKeyModel(
            AelisCyclicRegionDetector.KeyModel<AEKey, CompiledCandidate> model) {
    }

    private record ExternalCyclicRequest(
            GraphConsumableInput input, BigInteger requests) {
    }

    private record RawExternalCyclicRequest(
            RawCycleInput input, BigInteger requests) {
    }

    private record CandidateProgressCheck(boolean noProgress) {
        private static final CandidateProgressCheck PROGRESS =
                new CandidateProgressCheck(false);
        private static final CandidateProgressCheck NO_PROGRESS =
                new CandidateProgressCheck(true);
    }

    private static final class EdgeAccumulator {
        private long requestMultiplier;
        private int occurrences = 1;

        private EdgeAccumulator(long requestMultiplier) {
            this.requestMultiplier = requestMultiplier;
        }
    }

    private static final class Graph {
        private final List<Node> nodes;
        private final int[] topologicalOrder;
        private final int rootIndex;
        private final long logicalNodeCount;
        private final long mergedOccurrences;
        private final int barrierCount;
        private final int orderedChoiceCount;
        private final boolean contextSensitive;
        private final boolean hasSubstituteInputs;
        private final boolean hasReusableInputs;
        private final boolean hasSecondaryOutputs;
        private final ICraftingService craftingService;
        private final List<LocalCyclicRegion> cyclicRegions;
        // Owned by one calculation session. Inventory and live pattern checks are never cached.
        private boolean cyclicModelPrepared;
        private Map<AEKey, List<AelisCyclicDemandSolver.Variant<AEKey, CompiledCandidate>>>
                cyclicVariants;

        private Graph(List<Node> nodes, int[] topologicalOrder, int rootIndex,
                long logicalNodeCount, long mergedOccurrences, int barrierCount,
                int orderedChoiceCount, boolean contextSensitive,
                boolean hasSubstituteInputs, boolean hasReusableInputs,
                ICraftingService craftingService) {
            this(nodes, topologicalOrder, rootIndex, logicalNodeCount, mergedOccurrences,
                    barrierCount, orderedChoiceCount, contextSensitive, hasSubstituteInputs,
                    hasReusableInputs, craftingService, collectCyclicRegions(nodes));
        }

        private Graph(List<Node> nodes, int[] topologicalOrder, int rootIndex,
                long logicalNodeCount, long mergedOccurrences, int barrierCount,
                int orderedChoiceCount, boolean contextSensitive,
                boolean hasSubstituteInputs, boolean hasReusableInputs,
                ICraftingService craftingService, List<LocalCyclicRegion> cyclicRegions) {
            this.nodes = nodes;
            this.topologicalOrder = topologicalOrder;
            this.rootIndex = rootIndex;
            this.logicalNodeCount = logicalNodeCount;
            this.mergedOccurrences = mergedOccurrences;
            this.barrierCount = barrierCount;
            this.orderedChoiceCount = orderedChoiceCount;
            this.contextSensitive = contextSensitive;
            this.hasSubstituteInputs = hasSubstituteInputs;
            this.hasReusableInputs = hasReusableInputs;
            this.hasSecondaryOutputs = nodes.stream().anyMatch(node -> node.reachable
                    && (!node.secondaryOutputs.isEmpty() || node.compiledCandidates.stream()
                            .anyMatch(candidate -> !candidate.secondaryOutputs.isEmpty())));
            this.craftingService = craftingService;
            this.cyclicRegions = List.copyOf(cyclicRegions);
        }

        private static List<LocalCyclicRegion> collectCyclicRegions(List<Node> nodes) {
            var seen = new IdentityHashMap<LocalCyclicRegion, Boolean>();
            var regions = new ArrayList<LocalCyclicRegion>();
            for (Node node : nodes) {
                if (node.localCyclicRegion != null
                        && seen.put(node.localCyclicRegion, Boolean.TRUE) == null) {
                    regions.add(node.localCyclicRegion);
                }
            }
            return regions;
        }

        private String executionSafetyFailure() {
            return null;
        }

        private boolean requiresNativeNodeCount() {
            return executionScope()
                    != AelisExecutionPolicy.Scope.PURE_TOPOLOGICAL;
        }

        private boolean hasLocalBoundaries() {
            for (Node node : nodes) {
                if (node.reachable && (node.barrier
                        || node.executionMode != ExecutionMode.PURE_FAST
                        || node.localCyclicRegion != null)) {
                    return true;
                }
            }
            return false;
        }

        private AelisExecutionPolicy.Scope executionScope() {
            return AelisExecutionPolicy.select(
                    contextSensitive || hasSecondaryOutputs, hasLocalBoundaries(),
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
        private CertifiedPrefixProbeFallback(String reason) {
            super(reason, null, false, false);
        }
    }
}
