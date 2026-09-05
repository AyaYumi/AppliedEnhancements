package com.github.appliedenhancements.crafting.aelis;

import java.util.Collections;
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
            Set<K> regionKeys = region.keys();
            for (var candidates : region.variants().values()) {
                for (var candidate : candidates) {
                    boolean internalEdge = candidate.inputs().stream()
                            .anyMatch(input -> regionKeys.contains(input.key()));
                    if (internalEdge) {
                        result.add(candidate.id());
                    }
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
