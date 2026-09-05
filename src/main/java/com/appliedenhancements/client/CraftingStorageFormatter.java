package com.appliedenhancements.client;

import java.util.Locale;

/** Formats AE2 crafting CPU byte usage independently from item-count abbreviations. */
public final class CraftingStorageFormatter {
    private static final String[] UNITS = { "KB", "MB", "GB", "TB", "PB", "EB" };

    private CraftingStorageFormatter() {
    }

    public static String formatBytes(long value) {
        if (value < 1_000) {
            return value + "B";
        }
        double scaled = value;
        int unit = -1;
        while (scaled >= 1_000 && unit + 1 < UNITS.length) {
            scaled /= 1_000.0;
            unit++;
        }
        String number = scaled >= 100 || scaled == Math.rint(scaled)
                ? String.format(Locale.ROOT, "%.0f", scaled)
                : String.format(Locale.ROOT, "%.1f", scaled);
        return number + UNITS[unit];
    }
}
