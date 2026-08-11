package com.github.appliedenhancements.crafting.maxfast;

import java.util.Arrays;

/**
 * Pure in-memory capacity solver for deterministic crafting DAGs.
 *
 * <p>The solver mirrors AE2's ordered candidate semantics, but operates on a
 * compact inventory array. Candidate probes therefore never touch the live
 * storage network. It intentionally models only behavior that can be proven
 * deterministic by {@link OmniMaxFastPlanner}; unsupported nodes are reported
 * to the caller so the existing transactional path can take over.</p>
 */
final class OmniSparseCapacitySolver {
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
    }

    static final class Node {
        private final int keyIndex;
        private final long requestUnit;
        private final NodeKind kind;
        private final Candidate[] candidates;

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
            int equivalentCandidatesSkipped) {
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
        UNSUPPORTED
    }

    private record Attempt(Status status, long[] inventory) {
    }

    private record AllocationAttempt(Status status, long allocated,
            long[] inventory) {
    }

    private record ChoiceAttempt(Status status, long remaining,
            long[] allocations) {
    }

    private final Model model;
    private long probes;
    private int equivalentCandidatesSkipped;

    private OmniSparseCapacitySolver(Model model) {
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
                    initialInventory, new long[model.keyCount], 0, 0);
        }

        var solver = new OmniSparseCapacitySolver(model);
        long[] workingInventory = initialInventory.clone();
        boolean[] activeNodes = new boolean[model.nodes.length];
        activeNodes[rootNodeIndex] = true;
        ChoiceAttempt result;
        try {
            result = solver.requestChoice(
                    rootNodeIndex, requested, workingInventory,
                    activeNodes, true, 0);
        } finally {
            activeNodes[rootNodeIndex] = false;
        }

        boolean supported = result.status != Status.UNSUPPORTED;
        boolean complete = result.status == Status.SUCCESS;
        return new Plan(supported, complete,
                supported ? result.remaining : requested,
                result.allocations, workingInventory,
                new long[model.keyCount],
                solver.probes, solver.equivalentCandidatesSkipped);
    }

    /**
     * Proves one aggregated simulation of candidate zero. AE2 simulation never
     * switches to a later candidate merely because terminal inputs are missing;
     * it records those inputs as missing and considers candidate zero applied.
     * This compact execution mirrors that behavior without replaying one pattern
     * at a time.
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
        if (root.kind != NodeKind.CRAFTABLE || root.candidates.length != 1) {
            return unsupportedPlan(model, rootNodeIndex, requested, initialInventory);
        }
        if (requested == 0) {
            return new Plan(true, true, 0, new long[] { 0 },
                    initialInventory, new long[model.keyCount], 0, 0);
        }

        var solver = new OmniSparseCapacitySolver(model);
        long[] workingInventory = initialInventory.clone();
        long[] missing = new long[model.keyCount];
        boolean[] activeNodes = new boolean[model.nodes.length];
        Status status = solver.requestSimulationNode(
                rootNodeIndex, requested, workingInventory,
                missing, activeNodes, 0);
        boolean supported = status != Status.UNSUPPORTED;
        boolean complete = status == Status.SUCCESS;
        return new Plan(supported, complete,
                complete ? 0 : requested,
                new long[] { complete ? requested : 0 },
                workingInventory, missing, solver.probes, 0);
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
                new long[keyCount], 0, 0);
    }

    private ChoiceAttempt requestChoice(int nodeIndex, long requested,
            long[] inventory, boolean[] activeNodes,
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
            if (hasEquivalentEarlierCandidate(
                    node, candidateIndex, candidate)) {
                equivalentCandidatesSkipped++;
                continue;
            }

            if (probes >= MAX_PROBES) {
                return new ChoiceAttempt(Status.UNSUPPORTED, requested, allocations);
            }
            Attempt full = attemptCandidate(
                    nodeIndex, candidateIndex, remaining,
                    inventory, activeNodes, depth);
            probes++;
            if (full.status == Status.UNSUPPORTED) {
                return new ChoiceAttempt(Status.UNSUPPORTED, requested, allocations);
            }
            if (full.status == Status.SUCCESS) {
                replaceInventory(inventory, full.inventory);
                allocations[candidateIndex] = remaining;
                remaining = 0;
                break;
            }

            boolean laterDistinct = hasLaterDistinctCandidate(
                    node, candidateIndex, candidate);
            if (!requireExactPartialAllocation && !laterDistinct) {
                return new ChoiceAttempt(Status.SHORTAGE, remaining, allocations);
            }

            AllocationAttempt partial = findMaximumAllocation(
                    nodeIndex, candidateIndex, remaining,
                    inventory, activeNodes, depth);
            if (partial.status == Status.UNSUPPORTED) {
                return new ChoiceAttempt(Status.UNSUPPORTED, requested, allocations);
            }
            if (partial.allocated > 0) {
                replaceInventory(inventory, partial.inventory);
                allocations[candidateIndex] = partial.allocated;
                remaining -= partial.allocated;
            }
        }

        return new ChoiceAttempt(
                remaining == 0 ? Status.SUCCESS : Status.SHORTAGE,
                remaining, allocations);
    }

    private boolean hasEquivalentEarlierCandidate(Node node, int currentIndex,
            Candidate current) {
        for (int index = 0; index < currentIndex; index++) {
            if (current.hasSameSparseDemand(node.candidates[index])) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLaterDistinctCandidate(Node node, int currentIndex,
            Candidate current) {
        for (int index = currentIndex + 1;
                index < node.candidates.length; index++) {
            if (!node.candidates[index].hasSameSparseDemand(current)) {
                return true;
            }
        }
        return false;
    }

    private AllocationAttempt findMaximumAllocation(int nodeIndex,
            int candidateIndex, long requested,
            long[] inventory, boolean[] activeNodes, int depth) {
        long low = 0;
        long high = requested;
        long[] bestInventory = inventory.clone();
        while (high - low > 1) {
            if (probes >= MAX_PROBES) {
                return new AllocationAttempt(
                        Status.UNSUPPORTED, 0, inventory);
            }
            long trialAmount = upperMidpoint(low, high);
            Attempt trial = attemptCandidate(
                    nodeIndex, candidateIndex, trialAmount,
                    inventory, activeNodes, depth);
            probes++;
            if (trial.status == Status.UNSUPPORTED) {
                return new AllocationAttempt(Status.UNSUPPORTED, 0, inventory);
            }
            if (trial.status == Status.SUCCESS) {
                low = trialAmount;
                bestInventory = trial.inventory;
            } else {
                high = trialAmount;
            }
        }
        return new AllocationAttempt(Status.SUCCESS, low, bestInventory);
    }

    private Attempt attemptCandidate(int nodeIndex, int candidateIndex,
            long requested, long[] inventory, boolean[] activeNodes,
            int depth) {
        long[] trialInventory = inventory.clone();
        Status status = requestForcedCandidate(
                nodeIndex, candidateIndex, requested,
                trialInventory, activeNodes, depth);
        return new Attempt(status, trialInventory);
    }

    private Status requestNode(int nodeIndex, long requested,
            long[] inventory, boolean[] activeNodes, int depth) {
        if (requested == 0) {
            return Status.SUCCESS;
        }
        if (depth >= MAX_NODE_DEPTH) {
            return Status.UNSUPPORTED;
        }
        if (nodeIndex < 0 || nodeIndex >= model.nodes.length
                || activeNodes[nodeIndex]) {
            return Status.UNSUPPORTED;
        }

        activeNodes[nodeIndex] = true;
        try {
            Node node = model.nodes[nodeIndex];
            if (node.kind == NodeKind.CRAFTABLE
                    && node.candidates.length > 1) {
                ChoiceAttempt choice = requestChoice(
                        nodeIndex, requested, inventory,
                        activeNodes, false, depth);
                return choice.status;
            }
            return requestForcedCandidate(
                    nodeIndex, 0, requested,
                    inventory, activeNodes, depth);
        } finally {
            activeNodes[nodeIndex] = false;
        }
    }

    private Status requestSimulationNode(int nodeIndex, long requested,
            long[] inventory, long[] missing,
            boolean[] activeNodes, int depth) {
        if (requested == 0) {
            return Status.SUCCESS;
        }
        if (depth >= MAX_NODE_DEPTH || probes >= MAX_PROBES
                || nodeIndex < 0 || nodeIndex >= model.nodes.length
                || activeNodes[nodeIndex]) {
            return Status.UNSUPPORTED;
        }

        probes++;
        activeNodes[nodeIndex] = true;
        try {
            Node node = model.nodes[nodeIndex];
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
                inventory[node.keyIndex] -=
                        node.requestUnit * extractedMultipliers;
            }
            long remaining = requested - extractedMultipliers;
            if (remaining == 0 || node.kind == NodeKind.EMITTER) {
                return Status.SUCCESS;
            }
            if (node.kind == NodeKind.TERMINAL) {
                long deficit = multiply(node.requestUnit, remaining);
                if (deficit < 0) {
                    return Status.UNSUPPORTED;
                }
                missing[node.keyIndex] = saturatingAdd(
                        missing[node.keyIndex], deficit);
                return Status.SUCCESS;
            }
            if (node.kind != NodeKind.CRAFTABLE
                    || node.candidates.length != 1) {
                return Status.UNSUPPORTED;
            }

            Candidate candidate = node.candidates[0];
            long remainingItems = multiply(node.requestUnit, remaining);
            if (remainingItems < 0) {
                return Status.UNSUPPORTED;
            }
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
                    inventory[input.targetIndex] -= input.multiplier;
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
                        inventory, missing, activeNodes, depth + 1);
                if (childStatus != Status.SUCCESS) {
                    return childStatus;
                }
            }

            for (int index = 0; index < leaseCount; index++) {
                int keyIndex = leasedKeys[index];
                inventory[keyIndex] = saturatingAdd(
                        inventory[keyIndex], leasedAmounts[index]);
            }

            long remainder = remainingItems % candidate.outputPerPattern;
            long surplus = remainder == 0
                    ? 0
                    : candidate.outputPerPattern - remainder;
            if (surplus > 0) {
                inventory[node.keyIndex] = saturatingAdd(
                        inventory[node.keyIndex], surplus);
            }
            return Status.SUCCESS;
        } finally {
            activeNodes[nodeIndex] = false;
        }
    }

    private Status requestForcedCandidate(int nodeIndex, int candidateIndex,
            long requested, long[] inventory, boolean[] activeNodes,
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
            inventory[node.keyIndex] -= extractedItems;
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
                inventory[input.targetIndex] -= input.multiplier;
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
                    inventory, activeNodes, depth + 1);
            if (childStatus != Status.SUCCESS) {
                return childStatus;
            }
        }

        for (int index = 0; index < leaseCount; index++) {
            int keyIndex = leasedKeys[index];
            inventory[keyIndex] = saturatingAdd(
                    inventory[keyIndex], leasedAmounts[index]);
        }

        long remainder = requestedItems % candidate.outputPerPattern;
        long surplus = remainder == 0
                ? 0
                : candidate.outputPerPattern - remainder;
        if (surplus > 0) {
            inventory[node.keyIndex] = saturatingAdd(
                    inventory[node.keyIndex], surplus);
        }
        return Status.SUCCESS;
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
            inventory[template.keyIndex] -=
                    template.amount * extractedMultipliers;
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

    private static void replaceInventory(long[] destination, long[] source) {
        System.arraycopy(source, 0, destination, 0, destination.length);
    }
}
