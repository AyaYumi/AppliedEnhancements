package com.github.appliedenhancements.crafting.aelis;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Computes the extra cyclic demand needed to leave a startup seed behind. */
final class AelisCycleSeedReservation {
    private AelisCycleSeedReservation() {
    }

    static <K> Map<K, BigInteger> additions(
            Map<K, Long> minimumSeeds,
            Map<K, BigInteger> solvedSurplus,
            Map<K, BigInteger> retainedSeeds) {
        var additions = new LinkedHashMap<K, BigInteger>();
        for (var entry : minimumSeeds.entrySet()) {
            BigInteger minimum = BigInteger.valueOf(entry.getValue());
            BigInteger retained = value(retainedSeeds, entry.getKey());
            if (retained.signum() == 0
                    && value(solvedSurplus, entry.getKey()).compareTo(minimum) >= 0) {
                continue;
            }
            BigInteger addition = minimum.subtract(retained);
            if (addition.signum() > 0) {
                // Reserve the complete proven floor once a solve cannot leave
                // it as ordinary surplus. Adding only the current shortage can
                // oscillate with recipe batch remainders across solve passes.
                additions.put(entry.getKey(), addition);
            }
        }
        return Map.copyOf(additions);
    }

    private static <K> BigInteger value(Map<K, BigInteger> values, K key) {
        return values.getOrDefault(key, BigInteger.ZERO);
    }
}
