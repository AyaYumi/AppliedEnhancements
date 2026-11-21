package com.github.appliedenhancements.crafting.aelis;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded integer demand solver for finite cyclic crafting graphs.
 *
 * <p>Edges point from a pattern output to its inputs. Tarjan condensation
 * turns the graph into a DAG. Each component is solved after all predecessor
 * demand has arrived. Candidate variants are visited lazily in their supplied
 * order; a complete selection uses monotone integer balance iteration and a
 * compressed firing simulation to prove startup feasibility.</p>
 */
final class AelisCyclicDemandSolver<K, V> {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    record Input<K>(K key, BigInteger amount) {
        Input {
            Objects.requireNonNull(key, "key");
            requirePositive(amount, "input amount");
        }
    }

    record Variant<K, V>(V id, K output, BigInteger outputAmount,
            List<Input<K>> inputs) {
        Variant {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(output, "output");
            requirePositive(outputAmount, "output amount");
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }

    record Problem<K, V>(Map<K, List<Variant<K, V>>> variants,
            Map<K, BigInteger> demands, Map<K, BigInteger> available) {
        Problem {
            variants = copyVariants(variants, () -> {});
            demands = copyNonNegative(demands, "demand", () -> {});
            available = copyNonNegative(available, "available", () -> {});
        }
    }

    record Limits(int maxSccNodes, long maxSearchStates,
            long deadlineNanos, boolean exactAmounts) {
        Limits(int maxSccNodes, long maxSearchStates, long deadlineNanos) {
            this(maxSccNodes, maxSearchStates, deadlineNanos, false);
        }

        Limits {
            if (maxSccNodes <= 0 || maxSearchStates <= 0 || deadlineNanos <= 0) {
                throw new IllegalArgumentException("Invalid cyclic solver limits");
            }
        }
    }

    enum Failure {
        NONE,
        SCC_NODE_LIMIT,
        SEARCH_STATE_LIMIT,
        TIME_BUDGET,
        CANCELLED,
        NO_PRODUCER,
        NO_INTEGER_SOLUTION,
        LONG_RANGE_OVERFLOW
    }

    record Plan<K, V>(Map<V, BigInteger> firings,
            Map<K, BigInteger> missing,
            Map<K, BigInteger> requiredAvailable,
            Map<K, BigInteger> demands,
            Map<K, BigInteger> surplus,
            List<ScheduleStep<K, V>> executionSchedule,
            long exploredStates) {
        Plan {
            firings = Map.copyOf(firings);
            missing = Map.copyOf(missing);
            requiredAvailable = Map.copyOf(requiredAvailable);
            demands = Map.copyOf(demands);
            surplus = Map.copyOf(surplus);
            executionSchedule = List.copyOf(executionSchedule);
        }
    }

    record ScheduleStep<K, V>(
            V id,
            BigInteger firings,
            Map<K, BigInteger> inputsPerCraft,
            Set<K> selfReplenishingInputs) {
        ScheduleStep {
            Objects.requireNonNull(id, "id");
            requirePositive(firings, "schedule firings");
            inputsPerCraft = Map.copyOf(inputsPerCraft);
            selfReplenishingInputs = Set.copyOf(selfReplenishingInputs);
        }
    }

    record Result<K, V>(Plan<K, V> plan, Failure failure,
            long exploredStates) {
        boolean solved() {
            return plan != null && failure == Failure.NONE;
        }
    }

    /** One calculation's immutable recipe data and most recent traversal order. */
    static final class Model<K, V> {
        private final Map<K, List<Variant<K, V>>> variants;
        private Map<K, Set<K>> adjacency;
        private Topology<K> topology;
        private Set<V> cyclicMembers;

        private Model(Map<K, List<Variant<K, V>>> variants) {
            this.variants = variants;
        }

        static <K, V, E1 extends Exception, E2 extends Exception> Model<K, V> copyOf(
                Map<K, List<Variant<K, V>>> variants,
                AelisCyclicRegionDetector.Checkpoint<E1, E2> checkpoint) throws E1, E2 {
            return new Model<>(copyVariants(variants, checkpoint));
        }

        Map<K, List<Variant<K, V>>> variants() {
            return variants;
        }

        private Topology<K> topologyFor(Map<K, BigInteger> demands,
                Map<K, BigInteger> available) throws InterruptedException {
            var keys = new LinkedHashSet<K>();
            for (var group : List.of(demands.keySet(), available.keySet())) {
                for (K key : group) {
                    checkInterrupted();
                    keys.add(key);
                }
            }
            // Keep the previous solver's demand/stock-first ordering. It breaks
            // ties during seed scheduling, so a different order needs a new traversal.
            var startingKeys = List.copyOf(keys);
            if (topology != null && topology.startingKeys.equals(startingKeys)) {
                return topology;
            }
            if (adjacency == null) {
                adjacency = buildAdjacency(variants);
            }
            for (K key : adjacency.keySet()) {
                checkInterrupted();
                keys.add(key);
            }
            var components = tarjan(keys, adjacency);
            int[] componentOf = new int[keys.size()];
            var keyIndexes = indexKeys(keys);
            var largest = 0;
            for (int index = 0; index < components.size(); index++) {
                List<K> component = components.get(index);
                largest = Math.max(largest, component.size());
                for (K key : component) {
                    checkInterrupted();
                    componentOf[keyIndexes.get(key)] = index;
                }
            }
            var ordered = new ArrayList<Component<K>>(components.size());
            for (int index : componentTopologicalOrder(keys, components.size(), adjacency, componentOf, keyIndexes)) {
                checkInterrupted();
                var component = components.get(index);
                K first = component.get(0);
                ordered.add(new Component<>(component, component.size() > 1
                        || adjacency.getOrDefault(first, Set.of()).contains(first)));
            }
            topology = new Topology<>(startingKeys, List.copyOf(keys), List.copyOf(ordered), largest);
            return topology;
        }

        /** Called after a successful solve. Cycle membership is independent of traversal order. */
        Set<V> cyclicMembers() throws InterruptedException {
            checkInterrupted();
            if (cyclicMembers == null) {
                var members = new LinkedHashSet<V>();
                for (var component : topology.inOrder) {
                    checkInterrupted();
                    if (component.cyclic) {
                        AelisCyclicCraftingMembership.addComponentMembers(
                                variants, component.keys, members, AelisCyclicDemandSolver::checkInterrupted);
                    }
                }
                cyclicMembers = Collections.unmodifiableSet(members);
            }
            return cyclicMembers;
        }
    }

    private record Topology<K>(List<K> startingKeys, List<K> keys,
            List<Component<K>> inOrder, int largestComponent) {
    }

    private record Component<K>(List<K> keys, boolean cyclic) {
    }

    private final Model<K, V> model;
    private final Limits limits;
    private long exploredStates;
    private Failure stoppedBy = Failure.NONE;

    private AelisCyclicDemandSolver(Model<K, V> model, Limits limits) {
        this.model = model;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    static <K, V> Result<K, V> solve(Problem<K, V> problem, Limits limits) {
        var solver = new AelisCyclicDemandSolver<>(new Model<>(problem.variants), limits);
        try {
            return solver.solveChecked(problem.demands, problem.available);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return solver.failed(Failure.CANCELLED);
        }
    }

    static <K, V> Result<K, V> solve(Model<K, V> model, Map<K, BigInteger> demands,
            Map<K, BigInteger> available, Limits limits) throws InterruptedException {
        checkInterrupted();
        return new AelisCyclicDemandSolver<>(model, limits).solveChecked(
                copyNonNegative(demands, "demand", AelisCyclicDemandSolver::checkInterrupted),
                copyNonNegative(available, "available", AelisCyclicDemandSolver::checkInterrupted));
    }

    private Result<K, V> solveChecked(Map<K, BigInteger> demands, Map<K, BigInteger> available)
            throws InterruptedException {
        checkInterrupted();
        var result = solve(demands, available);
        checkInterrupted();
        return result;
    }

    private Result<K, V> solve(Map<K, BigInteger> requested, Map<K, BigInteger> available)
            throws InterruptedException {
        var topology = model.topologyFor(requested, available);
        if (topology.largestComponent > limits.maxSccNodes) {
            return failed(Failure.SCC_NODE_LIMIT);
        }

        var demands = new LinkedHashMap<K, BigInteger>(requested);
        var firings = new LinkedHashMap<V, BigInteger>();
        var missing = new LinkedHashMap<K, BigInteger>();
        var requiredAvailable = new LinkedHashMap<K, BigInteger>();
        var surplus = new LinkedHashMap<K, BigInteger>();
        var cyclicSchedules = new ArrayList<List<ScheduleStep<K, V>>>();

        for (var part : topology.inOrder) {
            if (!checkpoint()) {
                return failed(stoppedBy);
            }
            List<K> component = part.keys;
            boolean demanded = component.stream().anyMatch(
                    key -> positive(demands.get(key)));
            if (!demanded) {
                continue;
            }
            boolean hasProducer = component.stream().anyMatch(
                    key -> !model.variants.getOrDefault(key, List.of()).isEmpty());
            if (!hasProducer) {
                continue;
            }

            ComponentPlan<K, V> componentPlan = solveComponent(
                    component, available, demands);
            if (componentPlan == null) {
                return failed(stoppedBy == Failure.NONE
                        ? Failure.NO_INTEGER_SOLUTION : stoppedBy);
            }
            mergePositive(firings, componentPlan.firings);
            mergePositive(missing, componentPlan.missing);
            mergeMaximum(requiredAvailable, componentPlan.requiredAvailable);
            mergePositive(surplus, componentPlan.surplus);
            if (part.cyclic && !componentPlan.executionSchedule.isEmpty()) {
                cyclicSchedules.add(componentPlan.executionSchedule);
            }
            for (var entry : componentPlan.externalDemands.entrySet()) {
                demands.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }

        for (K key : topology.keys) {
            checkInterrupted();
            BigInteger demand = value(demands, key);
            if (!positive(demand)) {
                continue;
            }
            if (model.variants.getOrDefault(key, List.of()).isEmpty()) {
                BigInteger shortage = demand.subtract(value(available, key));
                if (shortage.signum() > 0) {
                    missing.merge(key, shortage, BigInteger::add);
                }
            }
            BigInteger directUse = value(available, key).min(demand);
            if (directUse.signum() > 0) {
                requiredAvailable.merge(key, directUse, BigInteger::max);
            }
        }

        if (!limits.exactAmounts && (firings.values().stream().anyMatch(value -> value.compareTo(LONG_MAX) > 0)
                || missing.values().stream().anyMatch(value -> value.compareTo(LONG_MAX) > 0)
                || demands.values().stream().anyMatch(value -> value.compareTo(LONG_MAX) > 0)
                || cyclicSchedules.stream().flatMap(List::stream)
                        .anyMatch(step -> step.firings.compareTo(LONG_MAX) > 0))) {
            return failed(Failure.LONG_RANGE_OVERFLOW);
        }

        var executionSchedule = new ArrayList<ScheduleStep<K, V>>();
        for (int index = cyclicSchedules.size() - 1; index >= 0; index--) {
            executionSchedule.addAll(cyclicSchedules.get(index));
        }

        return new Result<>(
                new Plan<>(firings, missing, requiredAvailable,
                        demands, surplus, executionSchedule, exploredStates),
                Failure.NONE, exploredStates);
    }

    private ComponentPlan<K, V> solveComponent(List<K> component,
            Map<K, BigInteger> available, Map<K, BigInteger> accumulatedDemands) throws InterruptedException {
        var choices = new ArrayList<List<Variant<K, V>>>(component.size());
        for (K key : component) {
            checkInterrupted();
            List<Variant<K, V>> variants = model.variants.getOrDefault(key, List.of());
            if (variants.isEmpty()) {
                BigInteger demand = value(accumulatedDemands, key);
                if (demand.compareTo(value(available, key)) > 0) {
                    stoppedBy = Failure.NO_PRODUCER;
                    return null;
                }
                choices.add(List.of());
            } else {
                choices.add(variants);
            }
        }
        @SuppressWarnings("unchecked")
        Variant<K, V>[] selected = (Variant<K, V>[]) new Variant<?, ?>[component.size()];
        return searchSelections(
                component, choices, selected, 0,
                available, accumulatedDemands);
    }

    private ComponentPlan<K, V> searchSelections(
            List<K> component,
            List<List<Variant<K, V>>> choices,
            Variant<K, V>[] selected,
            int index,
            Map<K, BigInteger> available,
            Map<K, BigInteger> accumulatedDemands) throws InterruptedException {
        if (!checkpoint()) {
            return null;
        }
        if (index == selected.length) {
            return solveSelection(component, selected, available, accumulatedDemands);
        }
        if (choices.get(index).isEmpty()) {
            selected[index] = null;
            return searchSelections(
                    component, choices, selected, index + 1,
                    available, accumulatedDemands);
        }
        for (Variant<K, V> variant : choices.get(index)) {
            if (!checkpoint()) {
                return null;
            }
            selected[index] = variant;
            ComponentPlan<K, V> result = searchSelections(
                    component, choices, selected, index + 1,
                    available, accumulatedDemands);
            if (result != null) {
                return result;
            }
            if (stoppedBy != Failure.NONE) {
                return null;
            }
        }
        selected[index] = null;
        return null;
    }

    private ComponentPlan<K, V> solveSelection(
            List<K> component, Variant<K, V>[] selected,
            Map<K, BigInteger> available,
            Map<K, BigInteger> accumulatedDemands) throws InterruptedException {
        int size = component.size();
        var indexes = indexKeys(component);
        var firings = new BigInteger[size];
        java.util.Arrays.fill(firings, ZERO);

        // required[i] is maintained incrementally when another firing count grows.
        // Self-consumption is already subtracted from effectiveOutput[i].
        var required = new BigInteger[size];
        var effectiveOutput = new BigInteger[size];
        var consumers = new ArrayList<List<DemandEdge>>(size);
        var pending = new ArrayDeque<Integer>();
        var queued = new boolean[size];
        for (int index = 0; index < size; index++) {
            checkInterrupted();
            K key = component.get(index);
            required[index] = value(accumulatedDemands, key).subtract(value(available, key));
            consumers.add(new ArrayList<>());
            Variant<K, V> variant = selected[index];
            if (variant == null) continue;
            effectiveOutput[index] = variant.outputAmount;
            for (Input<K> input : variant.inputs) {
                checkInterrupted();
                Integer target = indexes.get(input.key);
                if (target == null) continue;
                if (target == index) {
                    effectiveOutput[index] = effectiveOutput[index].subtract(input.amount);
                } else {
                    consumers.get(index).add(new DemandEdge(target, input.amount));
                }
            }
            pending.addLast(index);
            queued[index] = true;
        }
        long updates = 0;
        long updateLimit = Math.max(256L, (long) size * 256L) * size;
        while (!pending.isEmpty()) {
            if (!checkpoint()) {
                return null;
            }
            if (++updates > updateLimit) {
                return null;
            }
            int outputIndex = pending.removeFirst();
            queued[outputIndex] = false;
            if (required[outputIndex].signum() <= 0) continue;
            if (effectiveOutput[outputIndex].signum() <= 0) return null;
            BigInteger needed = ceilDivide(required[outputIndex], effectiveOutput[outputIndex]);
            if (needed.compareTo(firings[outputIndex]) <= 0) continue;
            if (!limits.exactAmounts && needed.compareTo(LONG_MAX) > 0) {
                stoppedBy = Failure.LONG_RANGE_OVERFLOW;
                return null;
            }
            BigInteger delta = needed.subtract(firings[outputIndex]);
            firings[outputIndex] = needed;
            for (DemandEdge edge : consumers.get(outputIndex)) {
                checkInterrupted();
                int target = edge.keyIndex;
                required[target] = required[target].add(edge.amount.multiply(delta));
                if (selected[target] != null && !queued[target]) {
                    pending.addLast(target);
                    queued[target] = true;
                }
            }
        }

        for (int keyIndex = 0; keyIndex < size; keyIndex++) {
            checkInterrupted();
            K key = component.get(keyIndex);
            BigInteger balance = value(available, key)
                    .subtract(value(accumulatedDemands, key));
            for (int pattern = 0; pattern < size; pattern++) {
                checkInterrupted();
                Variant<K, V> variant = selected[pattern];
                if (variant == null || firings[pattern].signum() == 0) {
                    continue;
                }
                if (key.equals(variant.output)) {
                    balance = balance.add(
                            variant.outputAmount.multiply(firings[pattern]));
                }
                BigInteger input = internalInputAmount(variant, key);
                if (input.signum() > 0) {
                    balance = balance.subtract(input.multiply(firings[pattern]));
                }
            }
            if (balance.signum() < 0) {
                return null;
            }
        }

        SeedSchedule<K, V> seedSchedule = proveFiringSchedule(
                component, selected, firings, available);
        if (seedSchedule == null) {
            return null;
        }

        var firingMap = new LinkedHashMap<V, BigInteger>();
        var externalDemands = new LinkedHashMap<K, BigInteger>();
        var componentSurplus = new LinkedHashMap<K, BigInteger>();
        for (int index = 0; index < size; index++) {
            Variant<K, V> variant = selected[index];
            BigInteger times = firings[index];
            if (variant == null || times.signum() == 0) {
                continue;
            }
            firingMap.merge(variant.id, times, BigInteger::add);
            for (Input<K> input : variant.inputs) {
                checkInterrupted();
                if (!indexes.containsKey(input.key)) {
                    externalDemands.merge(
                            input.key, input.amount.multiply(times), BigInteger::add);
                }
            }
        }
        for (K key : component) {
            BigInteger balance = value(available, key)
                    .add(value(seedSchedule.missing, key))
                    .subtract(value(accumulatedDemands, key));
            for (int pattern = 0; pattern < size; pattern++) {
                checkInterrupted();
                Variant<K, V> variant = selected[pattern];
                if (variant == null || firings[pattern].signum() == 0) {
                    continue;
                }
                if (key.equals(variant.output)) {
                    balance = balance.add(
                            variant.outputAmount.multiply(firings[pattern]));
                }
                balance = balance.subtract(
                        internalInputAmount(variant, key).multiply(firings[pattern]));
            }
            if (balance.signum() > 0) {
                componentSurplus.put(key, balance);
            }
        }
        return new ComponentPlan<>(
                firingMap, seedSchedule.missing,
                seedSchedule.requiredAvailable,
                externalDemands, componentSurplus,
                seedSchedule.executionSchedule);
    }

    private SeedSchedule<K, V> proveFiringSchedule(
            List<K> component, Variant<K, V>[] selected,
            BigInteger[] totalFirings, Map<K, BigInteger> available) throws InterruptedException {
        int size = component.size();
        BigInteger[] remaining = totalFirings.clone();
        var balance = new LinkedHashMap<K, BigInteger>();
        var minimumBalance = new LinkedHashMap<K, BigInteger>();
        var missing = new LinkedHashMap<K, BigInteger>();
        var executionSchedule = new ArrayList<ScheduleStep<K, V>>();
        for (K key : component) {
            balance.put(key, value(available, key));
            minimumBalance.put(key, value(available, key));
        }

        while (java.util.Arrays.stream(remaining).anyMatch(value -> value.signum() > 0)) {
            if (!checkpoint()) {
                return null;
            }
            int selectedPattern = -1;
            BigInteger selectedBatch = ZERO;
            for (int pattern = 0; pattern < size; pattern++) {
                checkInterrupted();
                if (remaining[pattern].signum() <= 0 || selected[pattern] == null) {
                    continue;
                }
                BigInteger capacity = remaining[pattern];
                Variant<K, V> variant = selected[pattern];
                for (K key : component) {
                    BigInteger inputAmount = internalInputAmount(variant, key);
                    if (inputAmount.signum() <= 0) {
                        continue;
                    }
                    if (key.equals(variant.output)
                            && variant.outputAmount.compareTo(inputAmount) >= 0
                            && value(balance, key).compareTo(inputAmount) >= 0) {
                        // The output is returned between firings, so one seed
                        // supports the entire remaining self-feedback batch.
                        continue;
                    }
                    capacity = capacity.min(
                            value(balance, key).divide(inputAmount));
                }
                if (capacity.signum() > 0) {
                    selectedPattern = pattern;
                    selectedBatch = capacity;
                    break;
                }
            }

            if (selectedPattern < 0) {
                BigInteger cheapest = null;
                for (int pattern = 0; pattern < size; pattern++) {
                    checkInterrupted();
                    if (remaining[pattern].signum() <= 0 || selected[pattern] == null) {
                        continue;
                    }
                    BigInteger deficit = ZERO;
                    Variant<K, V> variant = selected[pattern];
                    for (K key : component) {
                        BigInteger inputAmount = internalInputAmount(variant, key);
                        if (inputAmount.signum() <= 0) {
                            continue;
                        }
                        BigInteger shortfall = inputAmount
                                .subtract(value(balance, key));
                        if (shortfall.signum() > 0) {
                            deficit = deficit.add(shortfall);
                        }
                    }
                    if (cheapest == null || deficit.compareTo(cheapest) < 0) {
                        cheapest = deficit;
                        selectedPattern = pattern;
                    }
                }
                if (selectedPattern < 0) {
                    stoppedBy = Failure.NO_INTEGER_SOLUTION;
                    return null;
                }
                Variant<K, V> variant = selected[selectedPattern];
                for (K key : component) {
                    BigInteger inputAmount = internalInputAmount(variant, key);
                    if (inputAmount.signum() <= 0) {
                        continue;
                    }
                    BigInteger shortfall = inputAmount
                            .subtract(value(balance, key));
                    if (shortfall.signum() > 0) {
                        balance.merge(key, shortfall, BigInteger::add);
                        missing.merge(key, shortfall, BigInteger::add);
                    }
                }
                selectedBatch = ONE;
            }

            Variant<K, V> variant = selected[selectedPattern];
            var stepInputs = new LinkedHashMap<K, BigInteger>();
            var selfReplenishingInputs = new LinkedHashSet<K>();
            for (K key : component) {
                BigInteger inputAmount = internalInputAmount(variant, key);
                if (inputAmount.signum() <= 0) {
                    continue;
                }
                stepInputs.put(key, inputAmount);
                if (key.equals(variant.output)
                        && variant.outputAmount.compareTo(inputAmount) >= 0) {
                    selfReplenishingInputs.add(key);
                }
            }
            executionSchedule.add(new ScheduleStep<>(
                    variant.id, selectedBatch,
                    stepInputs, selfReplenishingInputs));
            boolean outputApplied = false;
            for (K key : component) {
                BigInteger inputAmount = internalInputAmount(variant, key);
                if (inputAmount.signum() <= 0) {
                    continue;
                }
                if (key.equals(variant.output)
                        && variant.outputAmount.compareTo(inputAmount) >= 0) {
                    BigInteger afterFirstConsumption = value(balance, key)
                            .subtract(inputAmount);
                    minimumBalance.merge(
                            key, afterFirstConsumption, BigInteger::min);
                    balance.merge(
                            key,
                            variant.outputAmount.subtract(inputAmount)
                                    .multiply(selectedBatch),
                            BigInteger::add);
                    outputApplied = true;
                } else {
                    balance.merge(
                            key,
                            inputAmount.multiply(selectedBatch).negate(),
                            BigInteger::add);
                    minimumBalance.merge(
                            key, value(balance, key), BigInteger::min);
                }
            }
            if (!outputApplied) {
                balance.merge(
                        variant.output,
                        variant.outputAmount.multiply(selectedBatch),
                        BigInteger::add);
            }
            remaining[selectedPattern] =
                    remaining[selectedPattern].subtract(selectedBatch);
        }
        var requiredAvailable = new LinkedHashMap<K, BigInteger>();
        for (K key : component) {
            BigInteger required = value(available, key)
                    .subtract(value(minimumBalance, key));
            if (required.signum() > 0) {
                requiredAvailable.put(key, required);
            }
        }
        return new SeedSchedule<>(
                missing, requiredAvailable, executionSchedule);
    }

    static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    private boolean checkpoint() throws InterruptedException {
        checkInterrupted();
        exploredStates++;
        if (exploredStates > limits.maxSearchStates) {
            stoppedBy = Failure.SEARCH_STATE_LIMIT;
            return false;
        }
        if (System.nanoTime() > limits.deadlineNanos) {
            stoppedBy = Failure.TIME_BUDGET;
            return false;
        }
        return true;
    }

    private Result<K, V> failed(Failure failure) {
        return new Result<>(null, failure, exploredStates);
    }

    private static <K, V> Map<K, Set<K>> buildAdjacency(
            Map<K, List<Variant<K, V>>> variants) throws InterruptedException {
        var adjacency = new LinkedHashMap<K, Set<K>>();
        for (var entry : variants.entrySet()) {
            checkInterrupted();
            Set<K> edges = adjacency.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>());
            for (Variant<K, V> variant : entry.getValue()) {
                checkInterrupted();
                for (Input<K> input : variant.inputs) {
                    checkInterrupted();
                    edges.add(input.key);
                    adjacency.computeIfAbsent(input.key, key -> new LinkedHashSet<>());
                }
            }
        }
        return adjacency;
    }

    private static <K> List<List<K>> tarjan(
            Collection<K> keys, Map<K, Set<K>> adjacency) throws InterruptedException {
        var state = new TarjanState<K>(adjacency);
        for (K key : keys) {
            checkInterrupted();
            if (!state.indexes.containsKey(key)) {
                state.visit(key);
            }
        }
        return List.copyOf(state.components);
    }

    private static <K> List<Integer> componentTopologicalOrder(
            Collection<K> keys, int componentCount, Map<K, Set<K>> adjacency,
            int[] componentOf, Map<K, Integer> keyIndexes) throws InterruptedException {
        var edges = new ArrayList<Set<Integer>>(componentCount);
        int[] indegrees = new int[componentCount];
        for (int index = 0; index < componentCount; index++) {
            edges.add(new LinkedHashSet<>());
        }
        for (K key : keys) {
            checkInterrupted();
            int from = componentOf[keyIndexes.get(key)];
            for (K targetKey : adjacency.getOrDefault(key, Set.of())) {
                checkInterrupted();
                int to = componentOf[keyIndexes.get(targetKey)];
                if (from != to && edges.get(from).add(to)) {
                    indegrees[to]++;
                }
            }
        }
        var queue = new ArrayDeque<Integer>();
        for (int index = 0; index < indegrees.length; index++) {
            if (indegrees[index] == 0) {
                queue.addLast(index);
            }
        }
        var order = new ArrayList<Integer>(componentCount);
        while (!queue.isEmpty()) {
            checkInterrupted();
            int component = queue.removeFirst();
            order.add(component);
            for (int target : edges.get(component)) {
                if (--indegrees[target] == 0) {
                    queue.addLast(target);
                }
            }
        }
        if (order.size() != componentCount) {
            throw new IllegalStateException("Condensed graph is not acyclic");
        }
        return order;
    }

    private static <K> Map<K, Integer> indexKeys(Collection<K> keys) throws InterruptedException {
        var indexes = new LinkedHashMap<K, Integer>();
        int index = 0;
        for (K key : keys) {
            checkInterrupted();
            indexes.put(key, index++);
        }
        return indexes;
    }

    private static <K, V> BigInteger internalInputAmount(
            Variant<K, V> variant, K key) throws InterruptedException {
        BigInteger result = ZERO;
        for (Input<K> input : variant.inputs) {
            checkInterrupted();
            if (key.equals(input.key)) {
                result = result.add(input.amount);
            }
        }
        return result;
    }

    private record DemandEdge(int keyIndex, BigInteger amount) {
    }

    private static BigInteger ceilDivide(BigInteger value, BigInteger divisor) {
        BigInteger[] division = value.divideAndRemainder(divisor);
        return division[0].add(division[1].signum() == 0 ? ZERO : ONE);
    }

    private static boolean positive(BigInteger value) {
        return value != null && value.signum() > 0;
    }

    private static <K> BigInteger value(Map<K, BigInteger> values, K key) {
        return values.getOrDefault(key, ZERO);
    }

    private static <K, E1 extends Exception, E2 extends Exception> Map<K, BigInteger> copyNonNegative(
            Map<K, BigInteger> values, String label,
            AelisCyclicRegionDetector.Checkpoint<E1, E2> checkpoint) throws E1, E2 {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<K, BigInteger>();
        for (var entry : values.entrySet()) {
            checkpoint.check();
            Objects.requireNonNull(entry.getKey(), label + " key");
            BigInteger value = Objects.requireNonNull(entry.getValue(), label + " value");
            if (value.signum() < 0) {
                throw new IllegalArgumentException(label + " must be non-negative");
            }
            if (value.signum() > 0) {
                copy.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(copy);
    }

    private static <K, V, E1 extends Exception, E2 extends Exception>
            Map<K, List<Variant<K, V>>> copyVariants(Map<K, List<Variant<K, V>>> variants,
                    AelisCyclicRegionDetector.Checkpoint<E1, E2> checkpoint) throws E1, E2 {
        if (variants == null || variants.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<K, List<Variant<K, V>>>();
        for (var entry : variants.entrySet()) {
            checkpoint.check();
            Objects.requireNonNull(entry.getKey(), "variant key");
            var list = new ArrayList<Variant<K, V>>();
            if (entry.getValue() != null) {
                for (Variant<K, V> variant : entry.getValue()) {
                    checkpoint.check();
                    if (!entry.getKey().equals(variant.output)) {
                        throw new IllegalArgumentException(
                                "Variant output does not match its key");
                    }
                    list.add(variant);
                }
            }
            copy.put(entry.getKey(), List.copyOf(list));
        }
        return Map.copyOf(copy);
    }

    private static <K, V> void mergePositive(
            Map<K, BigInteger> target, Map<K, BigInteger> addition) {
        for (var entry : addition.entrySet()) {
            if (entry.getValue().signum() > 0) {
                target.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }
    }

    private static <K> void mergeMaximum(
            Map<K, BigInteger> target, Map<K, BigInteger> addition) {
        for (var entry : addition.entrySet()) {
            if (entry.getValue().signum() > 0) {
                target.merge(entry.getKey(), entry.getValue(), BigInteger::max);
            }
        }
    }

    private static void requirePositive(BigInteger value, String label) {
        Objects.requireNonNull(value, label);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private record ComponentPlan<K, V>(Map<V, BigInteger> firings,
            Map<K, BigInteger> missing,
            Map<K, BigInteger> requiredAvailable,
            Map<K, BigInteger> externalDemands,
            Map<K, BigInteger> surplus,
            List<ScheduleStep<K, V>> executionSchedule) {
    }

    private record SeedSchedule<K, V>(Map<K, BigInteger> missing,
            Map<K, BigInteger> requiredAvailable,
            List<ScheduleStep<K, V>> executionSchedule) {
    }

    private static final class TarjanState<K> {
        private final Map<K, Set<K>> adjacency;
        private final Map<K, Integer> indexes = new HashMap<>();
        private final Map<K, Integer> lows = new HashMap<>();
        private final ArrayDeque<K> stack = new ArrayDeque<>();
        private final Set<K> onStack = new HashSet<>();
        private final List<List<K>> components = new ArrayList<>();
        private int nextIndex;

        private TarjanState(Map<K, Set<K>> adjacency) {
            this.adjacency = adjacency;
        }

        private void visit(K key) throws InterruptedException {
            var traversal = new ArrayDeque<TarjanFrame<K>>();
            enter(key, null, traversal);
            while (!traversal.isEmpty()) {
                checkInterrupted();
                TarjanFrame<K> frame = traversal.peek();
                if (frame.neighbors.hasNext()) {
                    K target = frame.neighbors.next();
                    if (!indexes.containsKey(target)) {
                        enter(target, frame.key, traversal);
                    } else if (onStack.contains(target)) {
                        lows.put(
                                frame.key,
                                Math.min(lows.get(frame.key), indexes.get(target)));
                    }
                    continue;
                }

                traversal.pop();
                if (frame.parent != null) {
                    lows.put(
                            frame.parent,
                            Math.min(lows.get(frame.parent), lows.get(frame.key)));
                }
                if (!Objects.equals(lows.get(frame.key), indexes.get(frame.key))) {
                    continue;
                }
                var component = new ArrayList<K>();
                K member;
                do {
                    checkInterrupted();
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(frame.key));
                components.add(List.copyOf(component));
            }
        }

        private void enter(K key, K parent,
                ArrayDeque<TarjanFrame<K>> traversal) {
            int index = nextIndex++;
            indexes.put(key, index);
            lows.put(key, index);
            stack.push(key);
            onStack.add(key);
            traversal.push(new TarjanFrame<>(
                    key, parent,
                    adjacency.getOrDefault(key, Set.of()).iterator()));
        }
    }

    private record TarjanFrame<K>(K key, K parent, Iterator<K> neighbors) {
    }
}
