package com.github.appliedenhancements.crafting.aelis;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Adds raw network candidates only for producer-less keys reached by the tree. */
final class AelisCyclicPatternOverlay {
    record Result<K, V>(
            Map<K, AelisCyclicRegionDetector.KeyModel<K, V>> models,
            Set<K> restoredKeys) {
        Result {
            models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
            restoredKeys = Collections.unmodifiableSet(new LinkedHashSet<>(restoredKeys));
        }
    }

    private AelisCyclicPatternOverlay() {
    }

    @FunctionalInterface
    interface CandidateLookup<K, V, E1 extends Exception, E2 extends Exception> {
        Collection<AelisCyclicDemandSolver.Variant<K, V>> find(K key) throws E1, E2;
    }

    static <K, V, E1 extends Exception, E2 extends Exception> Result<K, V> merge(
            Map<K, AelisCyclicRegionDetector.KeyModel<K, V>> existingModels,
            Collection<K> terminalKeys,
            CandidateLookup<K, V, E1, E2> lookup) throws E1, E2 {
        Objects.requireNonNull(existingModels, "existingModels");
        Objects.requireNonNull(terminalKeys, "terminalKeys");
        Objects.requireNonNull(lookup, "lookup");
        var models = new LinkedHashMap<K,
                AelisCyclicRegionDetector.KeyModel<K, V>>(existingModels);
        var restored = new LinkedHashSet<K>();
        var queried = new LinkedHashSet<K>();
        for (K key : terminalKeys) {
            if (key == null || !queried.add(key)) {
                continue;
            }
            var existing = models.get(key);
            if (existing != null && !existing.variants().isEmpty()) {
                continue;
            }
            Collection<AelisCyclicDemandSolver.Variant<K, V>> candidates =
                    lookup.find(key);
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            var variants = List.copyOf(candidates);
            models.put(key, new AelisCyclicRegionDetector.KeyModel<>(variants, true));
            restored.add(key);
        }
        return new Result<>(models, restored);
    }

    static <K, V> Map<K, List<AelisCyclicDemandSolver.Variant<K, V>>>
            mergeIntoGlobal(
                    Map<K, List<AelisCyclicDemandSolver.Variant<K, V>>> existing,
                    Collection<AelisCyclicRegionDetector.Region<K, V>> regions) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(regions, "regions");
        var result = new LinkedHashMap<K,
                List<AelisCyclicDemandSolver.Variant<K, V>>>();
        for (var entry : existing.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        for (var region : regions) {
            for (var entry : region.variants().entrySet()) {
                List<AelisCyclicDemandSolver.Variant<K, V>> current =
                        result.get(entry.getKey());
                if (current == null || current.isEmpty()) {
                    result.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
