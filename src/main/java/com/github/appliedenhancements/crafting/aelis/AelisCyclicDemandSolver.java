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
            variants = copyVariants(variants);
            demands = copyNonNegative(demands, "demand");
            available = copyNonNegative(available, "available");
        }
    }

    record Limits(int maxSccNodes, long maxSearchStates,
            long deadlineNanos) {
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

    private final Limits limits;
    private long exploredStates;
    private Failure stoppedBy = Failure.NONE;

    private AelisCyclicDemandSolver(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    static <K, V> Result<K, V> solve(Problem<K, V> problem, Limits limits) {
        return new AelisCyclicDemandSolver<K, V>(limits).solve(problem);
    }

    private Result<K, V> solve(Problem<K, V> problem) {
        Objects.requireNonNull(problem, "problem");
        var keys = collectKeys(problem);
        var adjacency = buildAdjacency(keys, problem.variants);
        List<List<K>> components = tarjan(keys, adjacency);
        for (List<K> component : components) {
            if (component.size() > limits.maxSccNodes) {
                return failed(Failure.SCC_NODE_LIMIT);
            }
        }

        int[] componentOf = new int[keys.size()];
        var keyIndexes = indexKeys(keys);
        for (int component = 0; component < components.size(); component++) {
            for (K key : components.get(component)) {
                componentOf[keyIndexes.get(key)] = component;
            }
        }
        List<Integer> componentOrder = componentTopologicalOrder(
                components, adjacency, componentOf, keyIndexes);

        var demands = new LinkedHashMap<K, BigInteger>();
        demands.putAll(problem.demands);
        var firings = new LinkedHashMap<V, BigInteger>();
        var missing = new LinkedHashMap<K, BigInteger>();
        var requiredAvailable = new LinkedHashMap<K, BigInteger>();
        var surplus = new LinkedHashMap<K, BigInteger>();
        var cyclicSchedules = new ArrayList<List<ScheduleStep<K, V>>>();

        for (int componentIndex : componentOrder) {
            if (!checkpoint()) {
                return failed(stoppedBy);
            }
            List<K> component = components.get(componentIndex);
            boolean demanded = component.stream().anyMatch(
                    key -> positive(demands.get(key)));
            if (!demanded) {
                continue;
            }
            boolean hasProducer = component.stream().anyMatch(
                    key -> !problem.variants.getOrDefault(key, List.of()).isEmpty());
            if (!hasProducer) {
                continue;
            }

            ComponentPlan<K, V> componentPlan = solveComponent(
                    component, problem, demands);
            if (componentPlan == null) {
                return failed(stoppedBy == Failure.NONE
                        ? Failure.NO_INTEGER_SOLUTION : stoppedBy);
            }
            mergePositive(firings, componentPlan.firings);
            mergePositive(missing, componentPlan.missing);
            mergeMaximum(requiredAvailable, componentPlan.requiredAvailable);
            mergePositive(surplus, componentPlan.surplus);
            boolean cyclic = component.size() > 1
                    || adjacency.getOrDefault(component.get(0), Set.of())
                            .contains(component.get(0));
            if (cyclic && !componentPlan.executionSchedule.isEmpty()) {
                cyclicSchedules.add(componentPlan.executionSchedule);
            }
            for (var entry : componentPlan.externalDemands.entrySet()) {
                demands.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }

        for (K key : keys) {
            BigInteger demand = value(demands, key);
            if (!positive(demand)) {
                continue;
            }
            if (problem.variants.getOrDefault(key, List.of()).isEmpty()) {
                BigInteger shortage = demand.subtract(value(problem.available, key));
                if (shortage.signum() > 0) {
                    missing.merge(key, shortage, BigInteger::add);
                }
            }
            BigInteger directUse = value(problem.available, key).min(demand);
            if (directUse.signum() > 0) {
                requiredAvailable.merge(key, directUse, BigInteger::max);
            }
        }

        if (firings.values().stream().anyMatch(value -> value.compareTo(LONG_MAX) > 0)
                || missing.values().stream().anyMatch(value -> value.compareTo(LONG_MAX) > 0)
                || demands.values().stream().anyMatch(value -> value.compareTo(LONG_MAX) > 0)
                || cyclicSchedules.stream().flatMap(List::stream)
                        .anyMatch(step -> step.firings.compareTo(LONG_MAX) > 0)) {
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
            Problem<K, V> problem, Map<K, BigInteger> accumulatedDemands) {
        var choices = new ArrayList<List<Variant<K, V>>>(component.size());
        for (K key : component) {
            List<Variant<K, V>> variants = problem.variants.getOrDefault(key, List.of());
            if (variants.isEmpty()) {
                BigInteger demand = value(accumulatedDemands, key);
                if (demand.compareTo(value(problem.available, key)) > 0) {
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
                problem, accumulatedDemands);
    }

    private ComponentPlan<K, V> searchSelections(
            List<K> component,
            List<List<Variant<K, V>>> choices,
            Variant<K, V>[] selected,
            int index,
            Problem<K, V> problem,
            Map<K, BigInteger> accumulatedDemands) {
        if (!checkpoint()) {
            return null;
        }
        if (index == selected.length) {
            return solveSelection(component, selected, problem, accumulatedDemands);
        }
        if (choices.get(index).isEmpty()) {
            selected[index] = null;
            return searchSelections(
                    component, choices, selected, index + 1,
                    problem, accumulatedDemands);
        }
        for (Variant<K, V> variant : choices.get(index)) {
            if (!checkpoint()) {
                return null;
            }
            selected[index] = variant;
            ComponentPlan<K, V> result = searchSelections(
                    component, choices, selected, index + 1,
                    problem, accumulatedDemands);
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
            Problem<K, V> problem,
            Map<K, BigInteger> accumulatedDemands) {
        int size = component.size();
        var indexes = indexKeys(component);
        var firings = new BigInteger[size];
        java.util.Arrays.fill(firings, ZERO);

        boolean changed;
        long selectionIterations = 0;
        long selectionIterationLimit = Math.max(256L, (long) size * 256L);
        do {
            if (!checkpoint()) {
                return null;
            }
            if (++selectionIterations > selectionIterationLimit) {
                return null;
            }
            changed = false;
            for (int outputIndex = 0; outputIndex < size; outputIndex++) {
                Variant<K, V> variant = selected[outputIndex];
                if (variant == null) {
                    continue;
                }
                K output = component.get(outputIndex);
                BigInteger effectiveOutput = variant.outputAmount.subtract(
                        internalInputAmount(variant, output));
                BigInteger required = value(accumulatedDemands, output)
                        .subtract(value(problem.available, output));
                for (int producer = 0; producer < size; producer++) {
                    if (producer == outputIndex) {
                        continue;
                    }
                    Variant<K, V> producerVariant = selected[producer];
                    if (producerVariant == null || firings[producer].signum() == 0) {
                        continue;
                    }
                    BigInteger perFiring = internalInputAmount(
                            producerVariant, output);
                    if (perFiring.signum() > 0) {
                        required = required.add(
                                perFiring.multiply(firings[producer]));
                    }
                }
                BigInteger needed;
                if (required.signum() <= 0) {
                    needed = ZERO;
                } else if (effectiveOutput.signum() <= 0) {
                    return null;
                } else {
                    needed = ceilDivide(required, effectiveOutput);
                }
                if (needed.compareTo(firings[outputIndex]) > 0) {
                    if (needed.compareTo(LONG_MAX) > 0) {
                        stoppedBy = Failure.LONG_RANGE_OVERFLOW;
                        return null;
                    }
                    firings[outputIndex] = needed;
                    changed = true;
                }
            }
        } while (changed);

        for (int keyIndex = 0; keyIndex < size; keyIndex++) {
            K key = component.get(keyIndex);
            BigInteger balance = value(problem.available, key)
                    .subtract(value(accumulatedDemands, key));
            for (int pattern = 0; pattern < size; pattern++) {
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
                component, selected, firings, problem.available);
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
                if (!indexes.containsKey(input.key)) {
                    externalDemands.merge(
                            input.key, input.amount.multiply(times), BigInteger::add);
                }
            }
        }
        for (K key : component) {
            BigInteger balance = value(problem.available, key)
                    .add(value(seedSchedule.missing, key))
                    .subtract(value(accumulatedDemands, key));
            for (int pattern = 0; pattern < size; pattern++) {
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
            BigInteger[] totalFirings, Map<K, BigInteger> available) {
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

    private boolean checkpoint() {
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

    private static <K, V> Set<K> collectKeys(Problem<K, V> problem) {
        var keys = new LinkedHashSet<K>();
        keys.addAll(problem.demands.keySet());
        keys.addAll(problem.available.keySet());
        for (var entry : problem.variants.entrySet()) {
            keys.add(entry.getKey());
            for (Variant<K, V> variant : entry.getValue()) {
                keys.add(variant.output);
                for (Input<K> input : variant.inputs) {
                    keys.add(input.key);
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    private static <K, V> Map<K, Set<K>> buildAdjacency(
            Collection<K> keys, Map<K, List<Variant<K, V>>> variants) {
        var adjacency = new LinkedHashMap<K, Set<K>>();
        for (K key : keys) {
            adjacency.put(key, new LinkedHashSet<>());
        }
        for (var entry : variants.entrySet()) {
            Set<K> edges = adjacency.get(entry.getKey());
            for (Variant<K, V> variant : entry.getValue()) {
                for (Input<K> input : variant.inputs) {
                    if (adjacency.containsKey(input.key)) {
                        edges.add(input.key);
                    }
                }
            }
        }
        return adjacency;
    }

    private static <K> List<List<K>> tarjan(
            Collection<K> keys, Map<K, Set<K>> adjacency) {
        var state = new TarjanState<K>(adjacency);
        for (K key : keys) {
            if (!state.indexes.containsKey(key)) {
                state.visit(key);
            }
        }
        return List.copyOf(state.components);
    }

    private static <K> List<Integer> componentTopologicalOrder(
            List<List<K>> components, Map<K, Set<K>> adjacency,
            int[] componentOf, Map<K, Integer> keyIndexes) {
        var edges = new ArrayList<Set<Integer>>(components.size());
        int[] indegrees = new int[components.size()];
        for (int index = 0; index < components.size(); index++) {
            edges.add(new LinkedHashSet<>());
        }
        for (var entry : adjacency.entrySet()) {
            int from = componentOf[keyIndexes.get(entry.getKey())];
            for (K targetKey : entry.getValue()) {
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
        var order = new ArrayList<Integer>(components.size());
        while (!queue.isEmpty()) {
            int component = queue.removeFirst();
            order.add(component);
            for (int target : edges.get(component)) {
                if (--indegrees[target] == 0) {
                    queue.addLast(target);
                }
            }
        }
        if (order.size() != components.size()) {
            throw new IllegalStateException("Condensed graph is not acyclic");
        }
        return order;
    }

    private static <K> Map<K, Integer> indexKeys(Collection<K> keys) {
        var indexes = new LinkedHashMap<K, Integer>();
        int index = 0;
        for (K key : keys) {
            indexes.put(key, index++);
        }
        return indexes;
    }

    private static <K, V> BigInteger internalInputAmount(
            Variant<K, V> variant, K key) {
        BigInteger result = ZERO;
        for (Input<K> input : variant.inputs) {
            if (key.equals(input.key)) {
                result = result.add(input.amount);
            }
        }
        return result;
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

    private static <K> Map<K, BigInteger> copyNonNegative(
            Map<K, BigInteger> values, String label) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<K, BigInteger>();
        for (var entry : values.entrySet()) {
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

    private static <K, V> Map<K, List<Variant<K, V>>> copyVariants(
            Map<K, List<Variant<K, V>>> variants) {
        if (variants == null || variants.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<K, List<Variant<K, V>>>();
        for (var entry : variants.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "variant key");
            var list = new ArrayList<Variant<K, V>>();
            if (entry.getValue() != null) {
                for (Variant<K, V> variant : entry.getValue()) {
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

        private void visit(K key) {
            var traversal = new ArrayDeque<TarjanFrame<K>>();
            enter(key, null, traversal);
            while (!traversal.isEmpty()) {
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
