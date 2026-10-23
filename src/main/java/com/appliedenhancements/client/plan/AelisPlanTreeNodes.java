package com.appliedenhancements.client.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Graph identities include recipe alternatives that have no material-summary quantities. */
public final class AelisPlanTreeNodes {
    public record Node<K>(K key, String name, String searchId, Optional<AelisPlanRows.Row<K>> amounts) {}

    private AelisPlanTreeNodes() {}

    public static <K> List<Node<K>> build(List<K> keys, List<AelisPlanRows.Row<K>> rows,
            Function<K, String> name, Function<K, String> searchId) {
        var byKey = new HashMap<K, AelisPlanRows.Row<K>>();
        for (var row : rows) byKey.put(row.key(), row);
        var nodes = new ArrayList<Node<K>>(keys.size());
        for (var key : keys) {
            var row = byKey.get(key);
            nodes.add(new Node<>(key, row == null ? name.apply(key) : row.name(),
                    row == null ? searchId.apply(key) : row.searchId(), Optional.ofNullable(row)));
        }
        return List.copyOf(nodes);
    }

    public static <K> Set<Integer> matches(List<Node<K>> nodes, AelisPlanRows.Filter filter, String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        var matches = new HashSet<Integer>();
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            if (filter != AelisPlanRows.Filter.ALL && !node.amounts().filter(filter::accepts).isPresent()) continue;
            if (needle.isEmpty() || node.name().toLowerCase(Locale.ROOT).contains(needle)
                    || node.searchId().toLowerCase(Locale.ROOT).contains(needle)) matches.add(i);
        }
        return Set.copyOf(matches);
    }
}
