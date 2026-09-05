package com.github.appliedenhancements.crafting.aelis;

/**
 * Overflow-safe helpers for CPU byte accounting on inputs that are physically
 * leased once but logically processed once per pattern.
 */
final class AelisByteAccounting {
    private AelisByteAccounting() {
    }

    static long totalLogicalVolume(long volumePerPattern, long patternTimes) {
        if (volumePerPattern < 0 || patternTimes < 0) {
            throw new IllegalArgumentException("Logical byte volume must be non-negative");
        }
        return Math.multiplyExact(volumePerPattern, patternTimes);
    }

    static long additionalRepeatedVolume(long initialVolume, long patternTimes) {
        if (initialVolume < 0 || patternTimes <= 0) {
            throw new IllegalArgumentException("Repeated byte volume needs a positive pattern count");
        }
        return Math.multiplyExact(initialVolume, patternTimes - 1);
    }
}
