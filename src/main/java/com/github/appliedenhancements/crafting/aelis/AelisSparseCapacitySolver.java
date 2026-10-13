package com.github.appliedenhancements.crafting.aelis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Pure in-memory capacity solver for deterministic crafting DAGs.
 *
 * <p>The solver mirrors AE2's ordered candidate semantics, but operates on a
 * compact inventory array. Candidate probes therefore never touch the live
 * storage network. It intentionally models only behavior that can be proven
 * deterministic by {@link AelisPlanner}; unsupported nodes are reported
 * to the caller so the existing transactional path can take over.</p>
 */
final class AelisSparseCapacitySolver {
    private static final long MAX_PROBES = 100_000;
    static final int MAX_NODE_DEPTH = 256;

    enum NodeKind {
        TERMINAL,
        EMITTER,
        CRAFTABLE,
        UNSUPPORTED
    }

    enum InputKind {
        CONSUMABLE,
        REUSABLE
    }

    record Template(int keyIndex, long amount) {
        Template {
            if (keyIndex < 0 || amount <= 0) {
                throw new IllegalArgumentException("Invalid sparse input template");
            }
        }
    }

    static final class Input {
        private final InputKind kind;
        private final int targetIndex;
        private final long multiplier;
        private final Template[] templates;

        private Input(InputKind kind, int targetIndex, long multiplier,
                Template[] templates) {
            if (kind == null || targetIndex < 0 || multiplier <= 0) {
                throw new IllegalArgumentException("Invalid sparse crafting input");
            }
            this.kind = kind;
            this.targetIndex = targetIndex;
            this.multiplier = multiplier;
            this.templates = templates == null ? null : templates.clone();
            if (this.templates != null) {
                for (Template template : this.templates) {
                    if (template == null) {
                        throw new IllegalArgumentException(
                                "Sparse input template must not be null");
                    }
                }
            }
        }

        static Input consumable(int nodeIndex, long multiplier) {
            return new Input(
                    InputKind.CONSUMABLE, nodeIndex, multiplier, null);
        }

        static Input substitutable(int nodeIndex, long multiplier,
                Template... templates) {
            return new Input(
                    InputKind.CONSUMABLE, nodeIndex, multiplier,
                    templates == null ? new Template[0] : templates);
        }

        static Input reusable(int keyIndex, long amount) {
            return new Input(InputKind.REUSABLE, keyIndex, amount, null);
        }

        private boolean hasExplicitTemplates() {
            return templates != null;
        }

        @Override
        public boolean equals(Object object) {
            return this == object
                    || object instanceof Input other
                            && kind == other.kind
                            && targetIndex == other.targetIndex
                            && multiplier == other.multiplier
                            && Arrays.equals(templates, other.templates);
        }

        @Override
        public int hashCode() {
            int result = kind.hashCode();
            result = 31 * result + targetIndex;
            result = 31 * result + Long.hashCode(multiplier);
            return 31 * result + Arrays.hashCode(templates);
        }
    }

    static final class Candidate {
        private final long outputPerPattern;
        private final Object equivalenceToken;
        private final Input[] inputs;

        Candidate(long outputPerPattern, Input... inputs) {
            this(outputPerPattern, null, inputs);
        }

        Candidate(long outputPerPattern, Object equivalenceToken,
                Input... inputs) {
            if (outputPerPattern <= 0) {
                throw new IllegalArgumentException("Candidate output must be positive");
            }
            this.outputPerPattern = outputPerPattern;
            this.equivalenceToken = equivalenceToken;
            this.inputs = inputs == null ? new Input[0] : inputs.clone();
            for (Input input : this.inputs) {
                if (input == null) {
                    throw new IllegalArgumentException("Candidate input must not be null");
                }
            }
        }

        long outputPerPattern() {
            return outputPerPattern;
        }

        Input[] inputs() {
            return inputs.clone();
        }

        private boolean hasSameSparseDemand(Candidate other) {
            return other != null
                    && equivalenceToken == other.equivalenceToken
                    && outputPerPattern == other.outputPerPattern
                    && Arrays.equals(inputs, other.inputs);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Candidate candidate && hasSameSparseDemand(candidate);
        }

        @Override public int hashCode() {
            int hash = 31 * System.identityHashCode(equivalenceToken) + Long.hashCode(outputPerPattern);
            return 31 * hash + Arrays.hashCode(inputs);
        }
    }

    static final class Node {
        private final int keyIndex;
        private final long requestUnit;
        private final NodeKind kind;
        private final Candidate[] candidates;
        private final boolean[] equivalentEarlier;
        private final boolean[] laterDistinct;

        Node(int keyIndex, long requestUnit, NodeKind kind, Candidate... candidates) {
            if (keyIndex < 0 || requestUnit <= 0 || kind == null) {
                throw new IllegalArgumentException("Invalid sparse crafting node");
            }
            this.keyIndex = keyIndex;
            this.requestUnit = requestUnit;
            this.kind = kind;
            this.candidates = candidates == null ? new Candidate[0] : candidates.clone();
            for (Candidate candidate : this.candidates) {
                if (candidate == null) {
                    throw new IllegalArgumentException("Node candidate must not be null");
                }
            }
            if (kind == NodeKind.CRAFTABLE && this.candidates.length == 0) {
                throw new IllegalArgumentException("Craftable node needs a candidate");
            }
            equivalentEarlier = new boolean[this.candidates.length];
            laterDistinct = new boolean[this.candidates.length];
            var first = new HashMap<Candidate, Integer>();
            for (int index = 0; index < this.candidates.length; index++) {
                equivalentEarlier[index] = first.putIfAbsent(this.candidates[index], index) != null;
            }
            for (int index = this.candidates.length - 2; index >= 0; index--) {
                laterDistinct[index] = laterDistinct[index + 1]
                        || !this.candidates[index].hasSameSparseDemand(this.candidates[index + 1]);
            }
        }

        static Node terminal(int keyIndex, long requestUnit) {
            return new Node(keyIndex, requestUnit, NodeKind.TERMINAL);
        }

        static Node emitter(int keyIndex, long requestUnit) {
            return new Node(keyIndex, requestUnit, NodeKind.EMITTER);
        }

        static Node craftable(int keyIndex, long requestUnit, Candidate... candidates) {
            return new Node(keyIndex, requestUnit, NodeKind.CRAFTABLE, candidates);
        }

        static Node unsupported(int keyIndex, long requestUnit) {
            return new Node(keyIndex, requestUnit, NodeKind.UNSUPPORTED);
        }

        int keyIndex() {
            return keyIndex;
        }

        long requestUnit() {
            return requestUnit;
        }

        NodeKind kind() {
            return kind;
        }

        Candidate[] candidates() {
            return candidates.clone();
        }
    }

    static final class Model {
        private final int keyCount;
        private final Node[] nodes;

        Model(int keyCount, Node... nodes) {
            if (keyCount < 0 || nodes == null) {
                throw new IllegalArgumentException("Invalid sparse capacity model");
            }
            this.keyCount = keyCount;
            this.nodes = nodes.clone();
            for (Node node : this.nodes) {
                if (node == null || node.keyIndex >= keyCount) {
                    throw new IllegalArgumentException("Node references an invalid inventory key");
                }
                for (Candidate candidate : node.candidates) {
                    for (Input input : candidate.inputs) {
                        int limit = input.kind == InputKind.CONSUMABLE
                                ? this.nodes.length
                                : keyCount;
                        if (input.targetIndex >= limit) {
                            throw new IllegalArgumentException(
                                    "Candidate references an invalid sparse target");
                        }
                        if (input.templates != null) {
                            for (Template template : input.templates) {
                                if (template.keyIndex >= keyCount) {
                                    throw new IllegalArgumentException(
                                            "Template references an invalid inventory key");
                                }
                            }
                        }
                    }
                }
            }
        }

        int keyCount() {
            return keyCount;
        }

        Node[] nodes() {
            return nodes.clone();
        }
    }

    record Plan(boolean supported, boolean complete, long remaining,
            long[] candidateAllocations, long[] endingInventory,
            long[] simulatedMissing, long probes,
            int equivalentCandidatesSkipped,
            int noProgressCandidatesSkipped) {
        Plan {
            candidateAllocations = candidateAllocations.clone();
            endingInventory = endingInventory.clone();
            simulatedMissing = simulatedMissing.clone();
        }

        @Override
        public long[] candidateAllocations() {
            return candidateAllocations.clone();
        }

        @Override
        public long[] endingInventory() {
            return endingInventory.clone();
        }

        @Override
        public long[] simulatedMissing() {
            return simulatedMissing.clone();
        }
    }

    private enum Status {
        SUCCESS,
        SHORTAGE,
        NO_PROGRESS,
        UNSUPPORTED
    }

    private record AllocationAttempt(Status status, long allocated) {
    }

    private record ChoiceAttempt(Status status, long remaining,
            long[] allocations) {
    }

    private final Model model;
    private final Trail trail = new Trail();
    private long probes;
    private int equivalentCandidatesSkipped;
    private int noProgressCandidatesSkipped;
    private int simulationRootCandidateIndex = -1;

    private AelisSparseCapacitySolver(Model model) {
        this.model = model;
    }

    static Plan plan(Model model, int rootNodeIndex, long requested,
            long[] initialInventory) {
        if (model == null || rootNodeIndex < 0
                || rootNodeIndex >= model.nodes.length
                || requested < 0 || initialInventory == null
                || initialInventory.length != model.keyCount) {
            return unsupportedPlan(model, rootNodeIndex, requested, initialInventory);
        }
        for (long amount : initialInventory) {
            if (amount < 0) {
                return unsupportedPlan(model, rootNodeIndex, requested, initialInventory);
            }
        }

        Node root = model.nodes[rootNodeIndex];
        if (root.kind != NodeKind.CRAFTABLE || root.candidates.length == 0) {
            return unsupportedPlan(model, rootNodeIndex, requested, initialInventory);
        }
        if (requested == 0) {
            return new Plan(true, true, 0, new long[root.candidates.length],
                    initialInventory, new long[model.keyCount], 0, 0, 0);
        }

        var solver = new AelisSparseCapacitySolver(model);
        long[] workingInventory = initialInventory.clone();
        long[] activeComponentDeficits = inactiveComponentStates(model.keyCount);
        int rootComponent = root.keyIndex;
        activeComponentDeficits[rootComponent] = componentDeficit(
                root.requestUnit, requested, workingInventory[rootComponent]);
        ChoiceAttempt result;
        try {
            result = solver.requestChoice(
                    rootNodeIndex, requested, workingInventory,
                    activeComponentDeficits, true, 0);
        } finally {
            activeComponentDeficits[rootComponent] = -1;
        }

        boolean supported = result.status != Status.UNSUPPORTED;
        boolean complete = result.status == Status.SUCCESS;
        return new Plan(supported, complete,
                 supported ? result.remaining : requested,
                 result.allocations, workingInventory,
                 new long[model.keyCount],
                 solver.probes, solver.equivalentCandidatesSkipped,
                 solver.noProgressCandidatesSkipped);
    }

    /**
     * Proves one aggregated simulation of the first progressing candidate. AE2
     * simulation never switches merely because terminal inputs are missing; it
     * records those inputs as missing and considers that candidate applied. A
     * candidate whose component state repeats without reducing its deficit is
     * the sole exception and is pruned before the next ordered candidate is
     * tried.
     */
    static Plan planSimulationFirstCandidate(Model model, int rootNodeIndex,
            long requested, long[] initialInventory) {
        if (model == null || rootNodeIndex < 0
                || rootNodeIndex >= model.nodes.length
                || requested < 0 || initialInventory == null
                || initialInventory.length != model.keyCount) {
            return unsupportedPlan(model, rootNodeIndex, requested, initialInventory);
        }
        for (long amount : initialInventory) {
            if (amount < 0) {
                return unsupportedPlan(model, rootNodeIndex, requested, initialInventory);
            }
        }

        Node root = model.nodes[rootNodeIndex];
        if (root.kind != NodeKind.CRAFTABLE || root.candidates.length == 0) {
            return unsupportedPlan(model, rootNodeIndex, requested, initialInventory);
        }
        if (requested == 0) {
            return new Plan(true, true, 0, new long[root.candidates.length],
                    initialInventory, new long[model.keyCount], 0, 0, 0);
        }

        var solver = new AelisSparseCapacitySolver(model);
        long[] workingInventory = initialInventory.clone();
        long[] missing = new long[model.keyCount];
        long[] activeComponentDeficits = inactiveComponentStates(model.keyCount);
        Status status = solver.requestSimulationNode(
                rootNodeIndex, requested, workingInventory,
                missing, activeComponentDeficits, 0);
        boolean supported = status != Status.UNSUPPORTED;
        boolean complete = status == Status.SUCCESS;
        long[] allocations = new long[root.candidates.length];
        if (complete && solver.simulationRootCandidateIndex >= 0) {
            allocations[solver.simulationRootCandidateIndex] = requested;
        }
        return new Plan(supported, complete,
                complete ? 0 : requested,
                allocations,
                workingInventory, missing, solver.probes, 0,
                solver.noProgressCandidatesSkipped);
    }

    private static Plan unsupportedPlan(Model model, int rootNodeIndex,
            long requested, long[] inventory) {
        int candidates = 0;
        if (model != null && rootNodeIndex >= 0
                && rootNodeIndex < model.nodes.length
                && model.nodes[rootNodeIndex] != null) {
            candidates = model.nodes[rootNodeIndex].candidates.length;
        }
        int keyCount = model == null ? 0 : model.keyCount;
        long[] safeInventory = inventory != null && inventory.length == keyCount
                ? inventory
                : new long[keyCount];
        return new Plan(false, false, Math.max(0, requested),
                new long[candidates], safeInventory,
                new long[keyCount], 0, 0, 0);
    }

    private ChoiceAttempt requestChoice(int nodeIndex, long requested,
            long[] inventory, long[] activeComponentDeficits,
            boolean requireExactPartialAllocation, int depth) {
        Node node = model.nodes[nodeIndex];
        if (node.kind != NodeKind.CRAFTABLE || node.candidates.length == 0) {
            return new ChoiceAttempt(
                    Status.UNSUPPORTED, requested,
                    new long[node.candidates.length]);
        }

        long remaining = requested;
        long[] allocations = new long[node.candidates.length];
        for (int candidateIndex = 0;
                candidateIndex < node.candidates.length && remaining > 0;
                candidateIndex++) {
            Candidate candidate = node.candidates[candidateIndex];
            if (isDirectNoProgressCandidate(nodeIndex, candidate)) {
                noProgressCandidatesSkipped++;
                continue;
            }
            if (node.equivalentEarlier[candidateIndex]) {
                equivalentCandidatesSkipped++;
                continue;
            }

            if (probes >= MAX_PROBES) {
                return new ChoiceAttempt(Status.UNSUPPORTED, requested, allocations);
            }
            Status full = attemptCandidate(
                    nodeIndex, candidateIndex, remaining,
                    inventory, activeComponentDeficits, depth, true);
            probes++;
            if (full == Status.NO_PROGRESS) {
                noProgressCandidatesSkipped++;
                continue;
            }
            if (full == Status.UNSUPPORTED) {
                return new ChoiceAttempt(Status.UNSUPPORTED, requested, allocations);
            }
            if (full == Status.SUCCESS) {
                allocations[candidateIndex] = remaining;
                remaining = 0;
                break;
            }

            boolean laterDistinct = node.laterDistinct[candidateIndex];
            if (!requireExactPartialAllocation && !laterDistinct) {
                return new ChoiceAttempt(Status.SHORTAGE, remaining, allocations);
            }

            AllocationAttempt partial = findMaximumAllocation(
                    nodeIndex, candidateIndex, remaining,
                    inventory, activeComponentDeficits, depth);
            if (partial.status == Status.UNSUPPORTED) {
                return new ChoiceAttempt(Status.UNSUPPORTED, requested, allocations);
            }
            if (partial.allocated > 0) {
                allocations[candidateIndex] = partial.allocated;
                remaining -= partial.allocated;
            }
        }

        return new ChoiceAttempt(
                remaining == 0 ? Status.SUCCESS : Status.SHORTAGE,
                remaining, allocations);
    }

    private AllocationAttempt findMaximumAllocation(int nodeIndex,
            int candidateIndex, long requested,
            long[] inventory, long[] activeComponentDeficits, int depth) {
        long low = 0;
        long high = requested;
        long direct = directTerminalCapacity(nodeIndex, candidateIndex, requested, inventory);
        if (direct >= 0) {
            low = direct;
            high = direct;
        }
        while (high - low > 1) {
            if (probes >= MAX_PROBES) {
                return new AllocationAttempt(
                        Status.UNSUPPORTED, 0);
            }
            long trialAmount = upperMidpoint(low, high);
            Status trial = attemptCandidate(
                    nodeIndex, candidateIndex, trialAmount,
                    inventory, activeComponentDeficits, depth, false);
            probes++;
            if (trial == Status.UNSUPPORTED) {
                return new AllocationAttempt(Status.UNSUPPORTED, 0);
            }
            if (trial == Status.SUCCESS) {
                low = trialAmount;
            } else {
                high = trialAmount;
            }
        }
        if (low > 0) {
            if (probes >= MAX_PROBES) return new AllocationAttempt(Status.UNSUPPORTED, 0);
            probes++;
            Status status = attemptCandidate(nodeIndex, candidateIndex, low,
                    inventory, activeComponentDeficits, depth, true);
            if (status != Status.SUCCESS) return new AllocationAttempt(Status.UNSUPPORTED, 0);
        }
        return new AllocationAttempt(Status.SUCCESS, low);
    }

    private Status attemptCandidate(int nodeIndex, int candidateIndex,
            long requested, long[] inventory, long[] activeComponentDeficits,
            int depth, boolean commit) {
        int mark = trail.mark();
        boolean applied = false;
        try {
            Status status = requestForcedCandidate(nodeIndex, candidateIndex, requested,
                    inventory, activeComponentDeficits, depth);
            applied = commit && status == Status.SUCCESS;
            return status;
        } finally {
            if (!applied) trail.rollback(mark);
        }
    }

    /** Exact only for raw consumable inputs; all repeated slots share one capacity bucket. */
    private long directTerminalCapacity(int nodeIndex, int candidateIndex, long requested,
            long[] inventory) {
        Node node = model.nodes[nodeIndex];
        Candidate candidate = node.candidates[candidateIndex];
        var perPattern = new LinkedHashMap<Integer, Long>();
        for (Input input : candidate.inputs) {
            if (input.kind != InputKind.CONSUMABLE || input.hasExplicitTemplates()) return -1;
            Node child = model.nodes[input.targetIndex];
            if (child.kind != NodeKind.TERMINAL || child.keyIndex == node.keyIndex) return -1;
            long amount = multiply(child.requestUnit, input.multiplier);
            if (amount < 0) return -1;
            long previous = perPattern.getOrDefault(child.keyIndex, 0L);
            if (previous > Long.MAX_VALUE - amount) return -1;
            perPattern.put(child.keyIndex, previous + amount);
        }
        long patterns = Long.MAX_VALUE;
        for (var entry : perPattern.entrySet()) {
            patterns = Math.min(patterns, inventory[entry.getKey()] / entry.getValue());
        }
        long produced = multiply(patterns, candidate.outputPerPattern);
        if (produced < 0) produced = Long.MAX_VALUE;
        return Math.min(requested, saturatingAdd(
                inventory[node.keyIndex] / node.requestUnit, produced / node.requestUnit));
    }

    private Status requestNode(int nodeIndex, long requested,
            long[] inventory, long[] activeComponentDeficits, int depth) {
        if (requested == 0) {
            return Status.SUCCESS;
        }
        if (depth >= MAX_NODE_DEPTH) {
            return Status.UNSUPPORTED;
        }
        if (nodeIndex < 0 || nodeIndex >= model.nodes.length) {
            return Status.UNSUPPORTED;
        }

        Node node = model.nodes[nodeIndex];
        int component = node.keyIndex;
        long deficit = componentDeficit(
                node.requestUnit, requested, inventory[component]);
        long previousDeficit = activeComponentDeficits[component];
        if (deficit > 0 && previousDeficit >= 0
                && deficit >= previousDeficit) {
            return Status.NO_PROGRESS;
        }
        boolean entered = deficit > 0;
        if (entered) {
            activeComponentDeficits[component] = deficit;
        }
        try {
            if (node.kind == NodeKind.CRAFTABLE
                    && node.candidates.length > 1) {
                ChoiceAttempt choice = requestChoice(
                        nodeIndex, requested, inventory,
                        activeComponentDeficits, false, depth);
                return choice.status;
            }
            return requestForcedCandidate(
                    nodeIndex, 0, requested,
                    inventory, activeComponentDeficits, depth);
        } finally {
            if (entered) {
                activeComponentDeficits[component] = previousDeficit;
            }
        }
    }

    private Status requestSimulationNode(int nodeIndex, long requested,
            long[] inventory, long[] missing,
            long[] activeComponentDeficits, int depth) {
        if (requested == 0) {
            return Status.SUCCESS;
        }
        if (depth >= MAX_NODE_DEPTH || probes >= MAX_PROBES
                || nodeIndex < 0 || nodeIndex >= model.nodes.length) {
            return Status.UNSUPPORTED;
        }

        probes++;
        Node node = model.nodes[nodeIndex];
        int component = node.keyIndex;
        long deficit = componentDeficit(
                node.requestUnit, requested, inventory[component]);
        long previousDeficit = activeComponentDeficits[component];
        if (deficit > 0 && previousDeficit >= 0
                && deficit >= previousDeficit) {
            noProgressCandidatesSkipped++;
            return Status.NO_PROGRESS;
        }
        boolean entered = deficit > 0;
        if (entered) {
            activeComponentDeficits[component] = deficit;
        }
        try {
            if (node.kind == NodeKind.UNSUPPORTED
                    || node.keyIndex >= inventory.length) {
                return Status.UNSUPPORTED;
            }

            long requestedItems = multiply(node.requestUnit, requested);
            if (requestedItems < 0) {
                return Status.UNSUPPORTED;
            }
            long availableMultipliers = inventory[node.keyIndex]
                    / node.requestUnit;
            long extractedMultipliers = Math.min(
                    requested, availableMultipliers);
            if (extractedMultipliers > 0) {
                trail.set(inventory, node.keyIndex, inventory[node.keyIndex]
                        - node.requestUnit * extractedMultipliers);
            }
            long remaining = requested - extractedMultipliers;
            if (remaining == 0 || node.kind == NodeKind.EMITTER) {
                return Status.SUCCESS;
            }
            if (node.kind == NodeKind.TERMINAL) {
                long missingAmount = multiply(node.requestUnit, remaining);
                if (missingAmount < 0) {
                    return Status.UNSUPPORTED;
                }
                trail.set(missing, node.keyIndex, saturatingAdd(
                        missing[node.keyIndex], missingAmount));
                return Status.SUCCESS;
            }
            if (node.kind != NodeKind.CRAFTABLE
                    || node.candidates.length == 0) {
                return Status.UNSUPPORTED;
            }

            long remainingItems = multiply(node.requestUnit, remaining);
            if (remainingItems < 0) {
                return Status.UNSUPPORTED;
            }
            for (int candidateIndex = 0;
                    candidateIndex < node.candidates.length; candidateIndex++) {
                Candidate candidate = node.candidates[candidateIndex];
                if (isDirectNoProgressCandidate(nodeIndex, candidate)) {
                    noProgressCandidatesSkipped++;
                    continue;
                }
                int mark = trail.mark();
                Status candidateStatus = requestSimulationCandidate(
                        node, candidate, remainingItems,
                        inventory, missing,
                        activeComponentDeficits, depth);
                if (candidateStatus != Status.SUCCESS) trail.rollback(mark);
                if (candidateStatus == Status.NO_PROGRESS) {
                    noProgressCandidatesSkipped++;
                    continue;
                }
                if (candidateStatus != Status.SUCCESS) {
                    return candidateStatus;
                }
                if (depth == 0) {
                    simulationRootCandidateIndex = candidateIndex;
                }
                return Status.SUCCESS;
            }
            return Status.NO_PROGRESS;
        } finally {
            if (entered) {
                activeComponentDeficits[component] = previousDeficit;
            }
        }
    }

    private Status requestSimulationCandidate(Node node, Candidate candidate,
            long remainingItems, long[] inventory, long[] missing,
            long[] activeComponentDeficits, int depth) {
        long patternTimes = ceilDiv(
                remainingItems, candidate.outputPerPattern);
        if (patternTimes < 0) {
            return Status.UNSUPPORTED;
        }

        int[] leasedKeys = new int[candidate.inputs.length];
        long[] leasedAmounts = new long[candidate.inputs.length];
        int leaseCount = 0;
        for (Input input : candidate.inputs) {
            if (input.kind == InputKind.REUSABLE) {
                if (input.targetIndex >= inventory.length
                        || inventory[input.targetIndex] < input.multiplier) {
                    return Status.UNSUPPORTED;
                }
                trail.set(inventory, input.targetIndex, inventory[input.targetIndex] - input.multiplier);
                leasedKeys[leaseCount] = input.targetIndex;
                leasedAmounts[leaseCount] = input.multiplier;
                leaseCount++;
                continue;
            }

            long childRequest = multiply(input.multiplier, patternTimes);
            if (childRequest < 0) {
                return Status.UNSUPPORTED;
            }
            if (input.hasExplicitTemplates()) {
                childRequest = extractTemplateMultipliers(
                        input.templates, childRequest, inventory);
            }
            Status childStatus = requestSimulationNode(
                    input.targetIndex, childRequest,
                    inventory, missing, activeComponentDeficits, depth + 1);
            if (childStatus != Status.SUCCESS) {
                return childStatus;
            }
        }

        for (int index = 0; index < leaseCount; index++) {
            int keyIndex = leasedKeys[index];
            trail.set(inventory, keyIndex, saturatingAdd(
                    inventory[keyIndex], leasedAmounts[index]));
        }

        long remainder = remainingItems % candidate.outputPerPattern;
        long surplus = remainder == 0
                ? 0
                : candidate.outputPerPattern - remainder;
        if (surplus > 0) {
            trail.set(inventory, node.keyIndex, saturatingAdd(
                    inventory[node.keyIndex], surplus));
        }
        return Status.SUCCESS;
    }

    private Status requestForcedCandidate(int nodeIndex, int candidateIndex,
            long requested, long[] inventory, long[] activeComponentDeficits,
            int depth) {
        if (requested == 0) {
            return Status.SUCCESS;
        }
        Node node = model.nodes[nodeIndex];
        if (node.kind == NodeKind.UNSUPPORTED
                || node.keyIndex >= inventory.length) {
            return Status.UNSUPPORTED;
        }
        if (multiply(node.requestUnit, requested) < 0) {
            return Status.UNSUPPORTED;
        }

        long availableItems = inventory[node.keyIndex];
        long availableMultipliers = availableItems / node.requestUnit;
        long extractedMultipliers = Math.min(requested, availableMultipliers);
        if (extractedMultipliers > 0) {
            long extractedItems = node.requestUnit * extractedMultipliers;
            trail.set(inventory, node.keyIndex, inventory[node.keyIndex] - extractedItems);
        }
        long remaining = requested - extractedMultipliers;
        if (remaining == 0 || node.kind == NodeKind.EMITTER) {
            return Status.SUCCESS;
        }
        if (node.kind == NodeKind.TERMINAL) {
            return Status.SHORTAGE;
        }
        if (node.kind != NodeKind.CRAFTABLE
                || candidateIndex < 0
                || candidateIndex >= node.candidates.length) {
            return Status.UNSUPPORTED;
        }

        Candidate candidate = node.candidates[candidateIndex];
        long requestedItems = multiply(node.requestUnit, remaining);
        if (requestedItems < 0) {
            return Status.UNSUPPORTED;
        }
        long patternTimes = ceilDiv(requestedItems, candidate.outputPerPattern);
        if (patternTimes < 0) {
            return Status.UNSUPPORTED;
        }

        int[] leasedKeys = new int[candidate.inputs.length];
        long[] leasedAmounts = new long[candidate.inputs.length];
        int leaseCount = 0;
        for (Input input : candidate.inputs) {
            if (input.kind == InputKind.REUSABLE) {
                if (input.targetIndex >= inventory.length
                        || inventory[input.targetIndex] < input.multiplier) {
                    return Status.SHORTAGE;
                }
                trail.set(inventory, input.targetIndex, inventory[input.targetIndex] - input.multiplier);
                leasedKeys[leaseCount] = input.targetIndex;
                leasedAmounts[leaseCount] = input.multiplier;
                leaseCount++;
                continue;
            }

            long childRequest = multiply(input.multiplier, patternTimes);
            if (childRequest < 0) {
                return Status.UNSUPPORTED;
            }
            if (input.hasExplicitTemplates()) {
                childRequest = extractTemplateMultipliers(
                        input.templates, childRequest, inventory);
            }
            if (childRequest == 0) {
                continue;
            }
            Status childStatus = requestNode(
                    input.targetIndex, childRequest,
                    inventory, activeComponentDeficits, depth + 1);
            if (childStatus != Status.SUCCESS) {
                return childStatus;
            }
        }

        for (int index = 0; index < leaseCount; index++) {
            int keyIndex = leasedKeys[index];
            trail.set(inventory, keyIndex, saturatingAdd(
                    inventory[keyIndex], leasedAmounts[index]));
        }

        long remainder = requestedItems % candidate.outputPerPattern;
        long surplus = remainder == 0
                ? 0
                : candidate.outputPerPattern - remainder;
        if (surplus > 0) {
            trail.set(inventory, node.keyIndex, saturatingAdd(
                    inventory[node.keyIndex], surplus));
        }
        return Status.SUCCESS;
    }

    /**
     * Rejects an exact candidate that consumes at least as much of its own
     * output component as it produces. Such a branch cannot reduce the
     * outstanding demand regardless of the other inputs or available stock.
     * Substitutable inputs are deliberately excluded because another key may
     * satisfy them without feeding the component back into itself.
     */
    private boolean isDirectNoProgressCandidate(
            int nodeIndex, Candidate candidate) {
        Node owner = model.nodes[nodeIndex];
        long recursiveDemand = 0;
        for (Input input : candidate.inputs) {
            if (input.kind != InputKind.CONSUMABLE
                    || input.hasExplicitTemplates()) {
                continue;
            }
            Node child = model.nodes[input.targetIndex];
            if (child.keyIndex != owner.keyIndex) {
                continue;
            }
            long demand = multiply(child.requestUnit, input.multiplier);
            if (demand < 0) {
                return false;
            }
            recursiveDemand = saturatingAdd(recursiveDemand, demand);
        }
        return recursiveDemand > 0
                && recursiveDemand >= candidate.outputPerPattern;
    }

    private static long[] inactiveComponentStates(int keyCount) {
        long[] states = new long[keyCount];
        Arrays.fill(states, -1);
        return states;
    }

    /** Returns the unsatisfied item count used as the component-state key. */
    private static long componentDeficit(
            long requestUnit, long requested, long available) {
        long demand = multiply(requestUnit, requested);
        if (demand < 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, demand - Math.min(demand, available));
    }

    private long extractTemplateMultipliers(Template[] templates,
            long requestedMultipliers, long[] inventory) {
        long remaining = requestedMultipliers;
        for (Template template : templates) {
            if (remaining == 0) {
                break;
            }
            long availableMultipliers = inventory[template.keyIndex]
                    / template.amount;
            long extractedMultipliers = Math.min(
                    remaining, availableMultipliers);
            if (extractedMultipliers == 0) {
                continue;
            }
            trail.set(inventory, template.keyIndex, inventory[template.keyIndex]
                    - template.amount * extractedMultipliers);
            remaining -= extractedMultipliers;
        }
        return remaining;
    }

    private static long multiply(long left, long right) {
        if (left < 0 || right < 0
                || (left != 0 && right > Long.MAX_VALUE / left)) {
            return -1;
        }
        return left * right;
    }

    private static long ceilDiv(long value, long divisor) {
        if (value < 0 || divisor <= 0) {
            return -1;
        }
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long upperMidpoint(long lowerInclusive, long upperInclusive) {
        long distance = upperInclusive - lowerInclusive;
        return lowerInclusive + distance / 2 + distance % 2;
    }

    /** Nested transactions retain only changed slots; no inventory-sized probe snapshots. */
    private static final class Trail {
        private long[][] arrays = new long[64][];
        private int[] indexes = new int[64];
        private long[] oldValues = new long[64];
        private int size;

        int mark() { return size; }

        void set(long[] array, int index, long value) {
            if (array[index] == value) return;
            if (size == indexes.length) {
                int capacity = Math.multiplyExact(size, 2);
                arrays = Arrays.copyOf(arrays, capacity);
                indexes = Arrays.copyOf(indexes, capacity);
                oldValues = Arrays.copyOf(oldValues, capacity);
            }
            arrays[size] = array;
            indexes[size] = index;
            oldValues[size++] = array[index];
            array[index] = value;
        }

        void rollback(int mark) {
            while (size > mark) {
                --size;
                arrays[size][indexes[size]] = oldValues[size];
                arrays[size] = null;
            }
        }
    }
}
