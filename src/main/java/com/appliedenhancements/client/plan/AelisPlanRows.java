package com.appliedenhancements.client.plan;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Unit-preserving presentation data. No totals across unlike item/fluid keys. */
public final class AelisPlanRows {
    private AelisPlanRows() {}

    public enum Filter {
        ALL, CRAFT, CYCLIC, MISSING;

        public boolean accepts(Row<?> row) {
            return switch (this) {
                case ALL -> true;
                case CRAFT -> row.craft().signum() > 0;
                case CYCLIC -> row.cyclic().signum() > 0;
                case MISSING -> row.missing().signum() > 0;
            };
        }
    }

    public record Row<K>(K key, String name, String searchId, BigInteger stored,
            BigInteger craft, BigInteger missing, BigInteger cyclic) {
        public BigInteger demand() {
            return stored.add(craft).add(missing);
        }
    }

    public static <K> BigInteger exact(Map<K, BigInteger> values, K key, long fallback) {
        return values.getOrDefault(key, BigInteger.valueOf(fallback)).max(BigInteger.ZERO);
    }

    public static <K> List<Row<K>> filter(List<Row<K>> rows, Filter filter, String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return rows.stream().filter(filter::accepts)
                .filter(row -> needle.isEmpty() || row.name().toLowerCase(Locale.ROOT).contains(needle)
                        || row.searchId().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.<Row<K>, Boolean>comparing(row -> row.missing().signum() == 0)
                        .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Row::searchId))
                .toList();
    }

    public static boolean canStart(boolean hasPlan, boolean simulation, boolean noCpu) {
        return hasPlan && !simulation && !noCpu;
    }

    /** Decide from the complete plan, never the current search/filter result. */
    public static boolean hasCyclicAmounts(List<? extends Row<?>> rows) {
        return rows.stream().anyMatch(row -> row.cyclic().signum() > 0);
    }

    public static int clampScroll(int requested, int count, int visible) {
        return Math.clamp(requested, 0, Math.max(0, count - visible));
    }
}
