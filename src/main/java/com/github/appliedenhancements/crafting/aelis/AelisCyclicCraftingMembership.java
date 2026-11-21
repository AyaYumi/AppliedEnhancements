package com.github.appliedenhancements.crafting.aelis;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Identifies pattern variants that actually contain an edge inside a cyclic SCC. */
final class AelisCyclicCraftingMembership {
    private AelisCyclicCraftingMembership() {
    }

    static <K, V> Set<V> find(
            Map<K, List<AelisCyclicDemandSolver.Variant<K, V>>> variants) {
        Objects.requireNonNull(variants, "variants");
        var models = new LinkedHashMap<K,
                AelisCyclicRegionDetector.KeyModel<K, V>>();
        for (var entry : variants.entrySet()) {
            models.put(entry.getKey(),
                    new AelisCyclicRegionDetector.KeyModel<>(entry.getValue(), true));
        }

        var result = new LinkedHashSet<V>();
        for (var region : AelisCyclicRegionDetector.detect(models)) {
            addComponentMembers(region.variants(), region.keys(), result, () -> {});
        }
        return Collections.unmodifiableSet(result);
    }

    static <K, V, E1 extends Exception, E2 extends Exception> void addComponentMembers(
            Map<K, List<AelisCyclicDemandSolver.Variant<K, V>>> variants, Collection<K> component,
            Set<V> members, AelisCyclicRegionDetector.Checkpoint<E1, E2> checkpoint) throws E1, E2 {
        var keys = new HashSet<>(component);
        for (K key : component) {
            checkpoint.check();
            for (var candidate : variants.getOrDefault(key, List.of())) {
                checkpoint.check();
                for (var input : candidate.inputs()) {
                    checkpoint.check();
                    if (keys.contains(input.key())) {
                        members.add(candidate.id());
                        break;
                    }
                }
            }
        }
    }
}
