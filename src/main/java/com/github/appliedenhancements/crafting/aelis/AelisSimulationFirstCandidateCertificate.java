package com.github.appliedenhancements.crafting.aelis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure proof used before an AE2 simulated ordered choice is aggregated into a
 * single first-candidate transaction.
 *
 * <p>The proof deliberately models only primary-output graphs with stable live
 * input templates. Besides rejecting cycles and stale candidates, it only
 * permits sibling inventory sharing when every touch goes through one
 * recursively proven, unit-sized graph service. Substitutable inputs claim
 * every non-selected inventory template they can currently consume; those
 * claims must remain isolated from descendants and sibling branches. It also
 * checks every selected input against all keys the transaction can produce.
 * The latter closes the case where a fuzzy match is absent in the initial
 * inventory but appears while the transaction is executing.</p>
 */
final class AelisSimulationFirstCandidateCertificate {
    record InventoryTemplate<K>(K key, long amount) {
        InventoryTemplate {
            Objects.requireNonNull(key, "key");
            if (amount <= 0) {
                throw new IllegalArgumentException(
                        "Inventory template amount must be positive");
            }
        }
    }

    record Input<K>(int childIndex, K selectedKey, long selectedAmount,
            long multiplier, boolean exact, boolean liveTemplatesSafe,
            List<InventoryTemplate<K>> inventoryTemplates,
            Predicate<K> acceptsKey) {
        Input {
            Objects.requireNonNull(selectedKey, "selectedKey");
            inventoryTemplates = inventoryTemplates == null
                    ? List.of() : List.copyOf(inventoryTemplates);
            Objects.requireNonNull(acceptsKey, "acceptsKey");
        }

        Input(int childIndex, K selectedKey, long selectedAmount,
                long multiplier, boolean exact, boolean liveTemplatesSafe,
                Predicate<K> acceptsKey) {
            this(childIndex, selectedKey, selectedAmount, multiplier,
                    exact, liveTemplatesSafe, List.of(), acceptsKey);
        }
    }

    record Node<K>(K key, long amount, boolean terminal, boolean emitter,
            boolean candidateShapeSafe, boolean candidate0Live,
            long outputPerPattern, int serviceId, List<Input<K>> inputs) {
        Node {
            Objects.requireNonNull(key, "key");
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }

        Node(K key, long amount, boolean terminal, boolean emitter,
                boolean candidateShapeSafe, boolean candidate0Live,
                long outputPerPattern, List<Input<K>> inputs) {
            this(key, amount, terminal, emitter, candidateShapeSafe,
                    candidate0Live, outputPerPattern, -1, inputs);
        }
    }

    record Result<K>(boolean safe, String reason,
            Set<K> produced, Set<K> consumed, Map<K, Integer> touchedOwners,
            boolean repeatComposable, String detail) {
        private static <K> Result<K> accept(Set<K> produced, Set<K> consumed,
                Map<K, Integer> touchedOwners, boolean repeatComposable) {
            return new Result<>(true, null,
                    Set.copyOf(produced), Set.copyOf(consumed),
                    Map.copyOf(touchedOwners),
                    repeatComposable, null);
        }

        private static <K> Result<K> reject(String reason) {
            return reject(reason, null);
        }

        private static <K> Result<K> reject(String reason, String detail) {
            return new Result<>(
                    false, reason, Set.of(), Set.of(), Map.of(), false,
                    detail);
        }
    }

    private static final int CONFLICTING_OWNER = -1;

    private AelisSimulationFirstCandidateCertificate() {
    }

    static <K> Result<K> evaluate(List<Node<K>> nodes, int rootIndex) {
        if (nodes == null || rootIndex < 0 || rootIndex >= nodes.size()) {
            return Result.reject("invalid_root");
        }
        @SuppressWarnings("unchecked")
        Result<K>[] memo = (Result<K>[]) new Result<?>[nodes.size()];
        byte[] states = new byte[nodes.size()];
        var selectedInputs = new ArrayList<Input<K>>();
        Result<K> root = evaluateNode(
                nodes, rootIndex, states, memo, selectedInputs);
        if (!root.safe()) {
            return root;
        }

        for (Input<K> input : selectedInputs) {
            for (K producedKey : root.produced()) {
                final boolean accepted;
                try {
                    accepted = input.acceptsKey().test(producedKey);
                } catch (RuntimeException exception) {
                    return Result.reject("input_validation_error");
                }
                if (accepted && !input.selectedKey().equals(producedKey)) {
                    return Result.reject("latent_fuzzy_crossfeed");
                }
            }
        }
        return root;
    }

    private static <K> Result<K> evaluateNode(List<Node<K>> nodes, int nodeIndex,
            byte[] states, Result<K>[] memo, List<Input<K>> selectedInputs) {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            return Result.reject("invalid_child");
        }
        if (states[nodeIndex] == 1) {
            return Result.reject("cycle");
        }
        if (states[nodeIndex] == 2) {
            return memo[nodeIndex];
        }

        Node<K> node = nodes.get(nodeIndex);
        if (node == null || node.amount() <= 0) {
            return Result.reject("invalid_node_amount");
        }
        if (node.emitter()) {
            return Result.reject("emitter");
        }
        states[nodeIndex] = 1;

        if (node.terminal()) {
            if (!node.inputs().isEmpty()) {
                states[nodeIndex] = 0;
                return Result.reject("terminal_with_inputs");
            }
            Result<K> result = Result.accept(
                    Set.of(), Set.of(node.key()),
                    Map.of(node.key(), nodeIndex), true);
            memo[nodeIndex] = result;
            states[nodeIndex] = 2;
            return result;
        }
        if (!node.candidateShapeSafe()) {
            states[nodeIndex] = 0;
            return Result.reject("unsafe_candidate_shape");
        }
        if (!node.candidate0Live()) {
            states[nodeIndex] = 0;
            return Result.reject("stale_candidate0");
        }
        if (node.outputPerPattern() <= 0) {
            states[nodeIndex] = 0;
            return Result.reject("invalid_output");
        }

        var childResults = new ArrayList<Result<K>>(node.inputs().size());
        long nominalInputVolume = 0;
        for (Input<K> input : node.inputs()) {
            if (input == null || !input.liveTemplatesSafe()
                    || input.multiplier() <= 0 || input.selectedAmount() <= 0) {
                states[nodeIndex] = 0;
                return Result.reject("inexact_input");
            }
            Node<K> child = input.childIndex() < 0
                    || input.childIndex() >= nodes.size()
                    ? null : nodes.get(input.childIndex());
            if (child == null || !child.key().equals(input.selectedKey())
                    || child.amount() != input.selectedAmount()) {
                states[nodeIndex] = 0;
                return Result.reject("input_child_mismatch");
            }
            try {
                nominalInputVolume = Math.addExact(
                        nominalInputVolume,
                        Math.multiplyExact(input.multiplier(), input.selectedAmount()));
            } catch (ArithmeticException exception) {
                states[nodeIndex] = 0;
                return Result.reject("input_overflow");
            }

            Result<K> childResult = evaluateNode(
                    nodes, input.childIndex(), states, memo, selectedInputs);
            if (!childResult.safe()) {
                states[nodeIndex] = 0;
                return childResult;
            }
            Result<K> claimedChildResult = claimSubstituteTemplates(
                    input, childResult, nodeIndex);
            if (!claimedChildResult.safe()) {
                states[nodeIndex] = 0;
                return claimedChildResult;
            }
            childResults.add(claimedChildResult);
            selectedInputs.add(input);
        }

        for (int left = 0; left < childResults.size(); left++) {
            Result<K> leftResult = childResults.get(left);
            if (leftResult.consumed().contains(node.key())) {
                states[nodeIndex] = 0;
                return Result.reject("owner_feedback");
            }
            for (int right = left + 1; right < childResults.size(); right++) {
                Result<K> rightResult = childResults.get(right);
                Set<K> crossfeed = intersection(
                        touchedKeys(leftResult), touchedKeys(rightResult));
                String conflict = findNonComposableCrossfeed(
                        crossfeed, leftResult, rightResult,
                        nodes, states, memo);
                if (conflict != null) {
                    states[nodeIndex] = 0;
                    return Result.reject("sibling_crossfeed", conflict);
                }
            }
        }

        var produced = new HashSet<K>();
        var consumed = new HashSet<K>();
        var touchedOwners = new HashMap<K, Integer>();
        produced.add(node.key());
        consumed.add(node.key());
        touchedOwners.put(node.key(), nodeIndex);
        for (Result<K> childResult : childResults) {
            produced.addAll(childResult.produced());
            consumed.addAll(childResult.consumed());
            mergeTouchedOwners(
                    touchedOwners, childResult.touchedOwners(), nodes);
        }
        boolean repeatComposable = node.amount() == 1;
        for (Result<K> childResult : childResults) {
            repeatComposable &= childResult.repeatComposable();
        }
        Result<K> result = Result.accept(
                produced, consumed, touchedOwners, repeatComposable);
        memo[nodeIndex] = result;
        states[nodeIndex] = 2;
        return result;
    }

    /**
     * A batched substitute request is equivalent to AE2's repeated request
     * loop only while its currently available alternatives are private to that
     * edge. The selected key is already owned by the recursively proven child;
     * every other template is represented as an intentionally unshareable
     * inventory claim.
     */
    private static <K> Result<K> claimSubstituteTemplates(
            Input<K> input, Result<K> childResult, int ownerIndex) {
        if (input.exact() || input.inventoryTemplates().isEmpty()) {
            return childResult;
        }

        Set<K> childTouched = touchedKeys(childResult);
        var consumed = new HashSet<K>(childResult.consumed());
        var touchedOwners = new HashMap<K, Integer>(
                childResult.touchedOwners());
        for (InventoryTemplate<K> template : input.inventoryTemplates()) {
            K templateKey = template.key();
            if (input.selectedKey().equals(templateKey)) {
                continue;
            }
            if (childTouched.contains(templateKey)) {
                return Result.reject(
                        "substitute_descendant_crossfeed",
                        "owner=" + ownerIndex
                                + ", selected=" + input.selectedKey()
                                + ", template=" + templateKey);
            }
            consumed.add(templateKey);
            touchedOwners.put(templateKey, CONFLICTING_OWNER);
        }
        return Result.accept(
                childResult.produced(), consumed, touchedOwners,
                childResult.repeatComposable());
    }

    private static <K> Set<K> touchedKeys(Result<K> result) {
        var touched = new HashSet<K>(result.produced());
        touched.addAll(result.consumed());
        return touched;
    }

    private static <K> Set<K> intersection(Set<K> left, Set<K> right) {
        var result = new HashSet<K>();
        Set<K> smaller = left.size() <= right.size() ? left : right;
        Set<K> larger = smaller == left ? right : left;
        for (K value : smaller) {
            if (larger.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static <K> void mergeTouchedOwners(Map<K, Integer> target,
            Map<K, Integer> source, List<Node<K>> nodes) {
        for (Map.Entry<K, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(),
                    (left, right) -> left.equals(right)
                            || shareCanonicalService(nodes, left, right)
                            ? left : CONFLICTING_OWNER);
        }
    }

    /**
     * Calling the same exact, unit-sized graph service from two sibling
     * branches is associative. Ownership is resolved inside the two branches,
     * so an unrelated occurrence of the same key elsewhere in the compiled
     * graph cannot invalidate an otherwise shared service.
     */
    private static <K> String findNonComposableCrossfeed(
            Set<K> crossfeed, Result<K> leftResult, Result<K> rightResult,
            List<Node<K>> nodes, byte[] states, Result<K>[] memo) {
        for (K key : crossfeed) {
            Integer leftOwner = leftResult.touchedOwners().get(key);
            Integer rightOwner = rightResult.touchedOwners().get(key);
            if (leftOwner == null || rightOwner == null
                    || leftOwner < 0 || rightOwner < 0) {
                return crossfeedDetail(
                        key, leftOwner, rightOwner, nodes,
                        "missing_or_conflicting_owner");
            }
            if (!shareCanonicalService(nodes, leftOwner, rightOwner)) {
                return crossfeedDetail(
                        key, leftOwner, rightOwner, nodes,
                        "different_service");
            }
            if (!isProvenComposableOwner(leftOwner, states, memo)
                    || !isProvenComposableOwner(rightOwner, states, memo)) {
                return crossfeedDetail(
                        key, leftOwner, rightOwner, nodes,
                        "non_composable_service");
            }
        }
        return null;
    }

    private static <K> String crossfeedDetail(K key,
            Integer leftOwner, Integer rightOwner, List<Node<K>> nodes,
            String cause) {
        return "key=" + key
                + ", cause=" + cause
                + ", left=" + describeOwner(leftOwner, nodes)
                + ", right=" + describeOwner(rightOwner, nodes);
    }

    private static <K> String describeOwner(
            Integer owner, List<Node<K>> nodes) {
        if (owner == null) {
            return "missing";
        }
        if (owner < 0 || owner >= nodes.size()) {
            return "conflict(" + owner + ")";
        }
        Node<K> node = nodes.get(owner);
        return "node(" + owner
                + ",key=" + node.key()
                + ",amount=" + node.amount()
                + ",service=" + node.serviceId()
                + ",terminal=" + node.terminal()
                + ",output=" + node.outputPerPattern()
                + ",inputs=" + node.inputs().size() + ")";
    }

    private static <K> boolean isProvenComposableOwner(int owner,
            byte[] states, Result<K>[] memo) {
        return owner >= 0 && owner < memo.length
                && states[owner] == 2
                && memo[owner] != null
                && memo[owner].safe()
                && memo[owner].repeatComposable();
    }

    /**
     * Context splitting may compile the same live pattern service into several
     * graph nodes. Node indexes therefore cannot be used as service identity.
     * A shared service is accepted only when the compiler supplied the same
     * canonical id and the complete exact-input shape remains identical.
     * Terminal inventory services are keyed by material, so their request
     * amounts may differ; the planner still preserves and charges each amount
     * independently against the shared inventory bucket.
     */
    private static <K> boolean shareCanonicalService(
            List<Node<K>> nodes, int leftIndex, int rightIndex) {
        if (leftIndex == rightIndex) {
            return leftIndex >= 0 && leftIndex < nodes.size();
        }
        if (leftIndex < 0 || rightIndex < 0
                || leftIndex >= nodes.size() || rightIndex >= nodes.size()) {
            return false;
        }

        var pending = new ArrayDeque<Long>();
        var visited = new HashSet<Long>();
        pending.addLast(pair(leftIndex, rightIndex));
        while (!pending.isEmpty()) {
            long current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            int leftNodeIndex = (int) (current >> 32);
            int rightNodeIndex = (int) current;
            if (leftNodeIndex == rightNodeIndex) {
                continue;
            }

            Node<K> left = nodes.get(leftNodeIndex);
            Node<K> right = nodes.get(rightNodeIndex);
            if (left.serviceId() < 0
                    || left.serviceId() != right.serviceId()
                    || !left.key().equals(right.key())
                    || left.terminal() != right.terminal()
                    || (!left.terminal()
                            && left.amount() != right.amount())
                    || left.emitter() != right.emitter()
                    || left.candidateShapeSafe() != right.candidateShapeSafe()
                    || left.candidate0Live() != right.candidate0Live()
                    || left.outputPerPattern() != right.outputPerPattern()
                    || left.inputs().size() != right.inputs().size()) {
                return false;
            }

            for (int inputIndex = 0;
                    inputIndex < left.inputs().size(); inputIndex++) {
                Input<K> leftInput = left.inputs().get(inputIndex);
                Input<K> rightInput = right.inputs().get(inputIndex);
                if (!leftInput.selectedKey().equals(rightInput.selectedKey())
                        || leftInput.selectedAmount()
                                != rightInput.selectedAmount()
                        || leftInput.multiplier() != rightInput.multiplier()
                        || leftInput.exact() != rightInput.exact()
                        || leftInput.liveTemplatesSafe()
                                != rightInput.liveTemplatesSafe()
                        || !leftInput.inventoryTemplates().equals(
                                rightInput.inventoryTemplates())
                        || leftInput.childIndex() < 0
                        || rightInput.childIndex() < 0
                        || leftInput.childIndex() >= nodes.size()
                        || rightInput.childIndex() >= nodes.size()) {
                    return false;
                }
                pending.addLast(pair(
                        leftInput.childIndex(), rightInput.childIndex()));
            }
        }
        return true;
    }

    private static long pair(int left, int right) {
        return (long) left << 32 | right & 0xffffffffL;
    }
}
