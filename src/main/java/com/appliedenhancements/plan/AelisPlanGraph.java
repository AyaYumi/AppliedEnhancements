package com.appliedenhancements.plan;

import appeng.api.stacks.AEKey;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded recipe dependencies for presentation, not an execution schedule or quantity ledger. */
public record AelisPlanGraph(List<AEKey> keys, List<Edge> edges, int root, boolean complete) {
    public static final int MAX_NODES = 512;
    public static final int MAX_EDGES = 2048;
    public static final AelisPlanGraph EMPTY = new AelisPlanGraph(List.of(), List.of(), -1, true);

    /** A consumer depends on an input; alternatives are possibilities, not proven consumed inputs. */
    public record Edge(int consumer, int input, boolean alternative) {}

    public AelisPlanGraph {
        keys = List.copyOf(keys);
        edges = List.copyOf(edges);
        if (keys.size() > MAX_NODES || edges.size() > MAX_EDGES
                || (keys.isEmpty() ? root != -1 : root < 0 || root >= keys.size())
                || new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException("Invalid plan graph bounds/root/keys");
        }
        var seen = new HashSet<Edge>();
        for (var edge : edges) {
            Objects.requireNonNull(edge, "edge");
            if (edge.consumer < 0 || edge.consumer >= keys.size()
                    || edge.input < 0 || edge.input >= keys.size() || !seen.add(edge)) {
                throw new IllegalArgumentException("Invalid plan graph edge");
            }
        }
    }
}
