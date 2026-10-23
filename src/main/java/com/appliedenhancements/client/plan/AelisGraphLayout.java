package com.appliedenhancements.client.plan;

import com.appliedenhancements.plan.AelisPlanGraph;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Iterative SCC analysis and bounded layout; no recursive unfolding of shared/cyclic recipes. */
public final class AelisGraphLayout {
    public static final int NODE_WIDTH = 116;
    public static final int NODE_HEIGHT = 44;
    public record Box(int node, double x, double y, int width, int height) {
        public Box(int node, double x, double y) {
            this(node, x, y, NODE_WIDTH, NODE_HEIGHT);
        }
    }
    public record NodeSize(int width, int height) {
        public NodeSize {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Node dimensions must be positive");
            }
        }
    }
    public record Group(int id, List<Integer> nodes, boolean cyclic, boolean collapsed,
            double x, double y, double width, double height) {}
    public record Drawing(List<Group> groups, List<Box> boxes, int[] groupOf, Set<Integer> matches) {}

    private static final NodeSize DEFAULT_SIZE = new NodeSize(NODE_WIDTH, NODE_HEIGHT);

    private AelisGraphLayout() {}

    public static Drawing build(AelisPlanGraph graph, Set<Integer> matches, Set<Integer> collapsed) {
        return build(graph, matches, collapsed, Map.of());
    }

    public static Drawing build(AelisPlanGraph graph, Set<Integer> matches, Set<Integer> collapsed,
            Map<Integer, NodeSize> sizes) {
        int n = graph.keys().size();
        var nodeSizes = new NodeSize[n];
        for (int node = 0; node < n; node++) nodeSizes[node] = sizes.getOrDefault(node, DEFAULT_SIZE);
        var forward = adjacency(n);
        var reverse = adjacency(n);
        for (var edge : graph.edges()) {
            if (edge.alternative()) continue; // Alternatives must not manufacture a proven cycle.
            forward.get(edge.consumer()).add(edge.input());
            reverse.get(edge.input()).add(edge.consumer());
        }
        var seen = new boolean[n];
        var order = new ArrayList<Integer>(n);
        for (int start = 0; start < n; start++) {
            if (seen[start]) continue;
            var stack = new ArrayDeque<int[]>();
            seen[start] = true;
            stack.push(new int[] {start, 0});
            while (!stack.isEmpty()) {
                var frame = stack.peek();
                var next = forward.get(frame[0]);
                if (frame[1] == next.size()) { order.add(frame[0]); stack.pop(); }
                else {
                    int child = next.get(frame[1]++);
                    if (!seen[child]) { seen[child] = true; stack.push(new int[] {child, 0}); }
                }
            }
        }
        int[] groupOf = new int[n];
        Arrays.fill(groupOf, -1);
        var members = new ArrayList<List<Integer>>();
        for (int i = order.size() - 1; i >= 0; i--) {
            int start = order.get(i);
            if (groupOf[start] >= 0) continue;
            int id = members.size();
            var group = new ArrayList<Integer>();
            var stack = new ArrayDeque<Integer>();
            stack.push(start);
            groupOf[start] = id;
            while (!stack.isEmpty()) {
                int node = stack.pop();
                group.add(node);
                for (int parent : reverse.get(node)) {
                    if (groupOf[parent] < 0) { groupOf[parent] = id; stack.push(parent); }
                }
            }
            group.sort(Integer::compareTo);
            members.add(List.copyOf(group));
        }
        // Preserve paths to a match and keep whole cyclic groups intact.
        var visible = new HashSet<Integer>();
        var parents = adjacency(members.size());
        var children = adjacency(members.size());
        var cyclic = new boolean[members.size()];
        for (int i = 0; i < members.size(); i++) cyclic[i] = members.get(i).size() > 1;
        for (var edge : graph.edges()) {
            int from = groupOf[edge.consumer()], to = groupOf[edge.input()];
            if (from != to) { parents.get(to).add(from); children.get(from).add(to); }
            else if (!edge.alternative() && edge.consumer() == edge.input()) cyclic[from] = true;
        }
        var pending = new ArrayDeque<Integer>();
        // Keep matched nodes' own ingredient branches, then retain the upstream route to the final output.
        for (int node : matches) {
            if (node >= 0 && node < n && visible.add(groupOf[node])) pending.add(groupOf[node]);
        }
        while (!pending.isEmpty()) {
            for (int child : children.get(pending.remove())) if (visible.add(child)) pending.add(child);
        }
        pending.addAll(visible);
        while (!pending.isEmpty()) {
            for (int parent : parents.get(pending.remove())) if (visible.add(parent)) pending.add(parent);
        }
        int[] depth = new int[members.size()];
        Arrays.fill(depth, -1);
        if (n > 0) { depth[groupOf[graph.root()]] = 0; pending.add(groupOf[graph.root()]); }
        while (!pending.isEmpty()) {
            int group = pending.remove();
            for (int child : children.get(group)) {
                if (depth[child] < 0) { depth[child] = depth[group] + 1; pending.add(child); }
            }
        }
        var sorted = new ArrayList<>(visible);
        sorted.sort(Comparator.<Integer>comparingInt(i -> depth[i] < 0 ? n : depth[i])
                .thenComparingInt(i -> members.get(i).getFirst()));
        var groups = new ArrayList<Group>();
        var boxes = new ArrayList<Box>();
        double x = 0, y = 0, rowHeight = 0;
        int previousDepth = -2;
        for (int id : sorted) {
            int layer = depth[id] < 0 ? n : depth[id];
            if (layer != previousDepth) {
                if (previousDepth != -2) y += rowHeight + 24;
                x = 0;
                rowHeight = 0;
                previousDepth = layer;
            }
            var nodes = members.get(id);
            boolean folded = cyclic[id] && collapsed.contains(nodes.getFirst());
            double w = 0, h = cyclic[id] ? 22 : 0;
            for (int node : nodes) {
                var size = nodeSizes[node];
                w = Math.max(w, size.width());
                h += size.height() + (cyclic[id] ? 5.0 : 0.0);
            }
            if (cyclic[id]) w += 12;
            if (folded) h = 20;
            groups.add(new Group(nodes.getFirst(), nodes, cyclic[id], folded, x, y, w, h));
            double nodeY = y + (cyclic[id] ? 19 : 0);
            if (!folded) for (int node : nodes) {
                var size = nodeSizes[node];
                boxes.add(new Box(node, x + (cyclic[id] ? 6 : 0), nodeY, size.width(), size.height()));
                nodeY += size.height() + 5.0;
            }
            x += w + 18;
            rowHeight = Math.max(rowHeight, h);
        }
        return new Drawing(List.copyOf(groups), List.copyOf(boxes), groupOf, Set.copyOf(matches));
    }

    private static List<List<Integer>> adjacency(int n) {
        var result = new ArrayList<List<Integer>>(n);
        for (int i = 0; i < n; i++) result.add(new ArrayList<>());
        return result;
    }
}
