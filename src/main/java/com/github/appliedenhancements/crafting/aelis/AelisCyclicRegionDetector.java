package com.github.appliedenhancements.crafting.aelis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Finds independently solvable cyclic regions inside a larger dependency graph. */
final class AelisCyclicRegionDetector<K, V> {
    record KeyModel<K, V>(
            List<AelisCyclicDemandSolver.Variant<K, V>> variants,
            boolean supported,
            String rejectionReason) {
        KeyModel(List<AelisCyclicDemandSolver.Variant<K, V>> variants,
                boolean supported) {
            this(variants, supported,
                    supported ? null : "producer is outside exact cyclic semantics");
        }

        KeyModel {
            variants = variants == null ? List.of() : List.copyOf(variants);
            if (supported) {
                rejectionReason = null;
            } else if (rejectionReason == null || rejectionReason.isBlank()) {
                rejectionReason = "producer is outside exact cyclic semantics";
            }
        }
    }

    record Region<K, V>(Set<K> keys,
            Map<K, List<AelisCyclicDemandSolver.Variant<K, V>>> variants) {
        Region {
            keys = Set.copyOf(keys);
            variants = Map.copyOf(variants);
        }
    }

    record RejectedRegion<K>(Set<K> keys, Map<K, String> reasons) {
        RejectedRegion {
            keys = Set.copyOf(keys);
            reasons = Map.copyOf(reasons);
        }
    }

    record Analysis<K, V>(List<Region<K, V>> regions,
            List<RejectedRegion<K>> rejectedRegions) {
        Analysis {
            regions = List.copyOf(regions);
            rejectedRegions = List.copyOf(rejectedRegions);
        }
    }

    private AelisCyclicRegionDetector() {
    }

    static <K, V> List<Region<K, V>> detect(Map<K, KeyModel<K, V>> models) {
        return analyze(models).regions;
    }

    static <K, V> Analysis<K, V> analyze(Map<K, KeyModel<K, V>> models) {
        Objects.requireNonNull(models, "models");
        var keys = new LinkedHashSet<K>();
        keys.addAll(models.keySet());
        for (KeyModel<K, V> model : models.values()) {
            if (model == null) {
                continue;
            }
            for (var variant : model.variants) {
                keys.add(variant.output());
                for (var input : variant.inputs()) {
                    keys.add(input.key());
                }
            }
        }

        var adjacency = new LinkedHashMap<K, Set<K>>();
        for (K key : keys) {
            adjacency.put(key, new LinkedHashSet<>());
        }
        for (var entry : models.entrySet()) {
            KeyModel<K, V> model = entry.getValue();
            if (model == null) {
                continue;
            }
            Set<K> edges = adjacency.get(entry.getKey());
            for (var variant : model.variants) {
                for (var input : variant.inputs()) {
                    edges.add(input.key());
                }
            }
        }

        var state = new TarjanState<K>(adjacency);
        for (K key : keys) {
            if (!state.indexes.containsKey(key)) {
                state.visit(key);
            }
        }

        var regions = new ArrayList<Region<K, V>>();
        var rejectedRegions = new ArrayList<RejectedRegion<K>>();
        for (List<K> component : state.components) {
            boolean cyclic = component.size() > 1
                    || adjacency.getOrDefault(component.getFirst(), Set.of())
                            .contains(component.getFirst());
            if (!cyclic) {
                continue;
            }
            var variants = new LinkedHashMap<K,
                    List<AelisCyclicDemandSolver.Variant<K, V>>>();
            var rejectionReasons = new LinkedHashMap<K, String>();
            boolean supported = true;
            for (K key : component) {
                KeyModel<K, V> model = models.get(key);
                if (model == null) {
                    rejectionReasons.put(key, "missing producer model");
                    supported = false;
                    continue;
                }
                if (!model.supported) {
                    rejectionReasons.put(key, model.rejectionReason);
                    supported = false;
                }
                if (model.variants.isEmpty()) {
                    rejectionReasons.putIfAbsent(key, "producer has no compiled candidates");
                    supported = false;
                    continue;
                }
                variants.put(key, model.variants);
            }
            if (supported) {
                regions.add(new Region<>(new LinkedHashSet<>(component), variants));
            } else {
                rejectedRegions.add(new RejectedRegion<>(
                        new LinkedHashSet<>(component), rejectionReasons));
            }
        }
        return new Analysis<>(regions, rejectedRegions);
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
            var traversal = new ArrayDeque<Frame<K>>();
            enter(key, null, traversal);
            while (!traversal.isEmpty()) {
                Frame<K> frame = traversal.peek();
                if (frame.neighbors.hasNext()) {
                    K target = frame.neighbors.next();
                    if (!indexes.containsKey(target)) {
                        enter(target, frame.key, traversal);
                    } else if (onStack.contains(target)) {
                        lows.put(frame.key,
                                Math.min(lows.get(frame.key), indexes.get(target)));
                    }
                    continue;
                }

                traversal.pop();
                if (frame.parent != null) {
                    lows.put(frame.parent,
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

        private void enter(K key, K parent, ArrayDeque<Frame<K>> traversal) {
            int index = nextIndex++;
            indexes.put(key, index);
            lows.put(key, index);
            stack.push(key);
            onStack.add(key);
            traversal.push(new Frame<>(key, parent,
                    adjacency.getOrDefault(key, Set.of()).iterator()));
        }
    }

    private record Frame<K>(K key, K parent, Iterator<K> neighbors) {
    }
}
