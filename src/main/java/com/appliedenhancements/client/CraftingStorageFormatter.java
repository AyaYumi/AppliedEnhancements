package com.appliedenhancements.client;

import java.math.BigInteger;
import com.appliedenhancements.util.AmountFormatter;

/** Formats AE2 crafting CPU byte usage independently from item-count abbreviations. */
public final class CraftingStorageFormatter {
    private CraftingStorageFormatter() {
    }

    public static String formatBytes(long value) {
        return formatBytes(BigInteger.valueOf(value));
    }

    public static String formatBytes(BigInteger value) {
        return AmountFormatter.format(value) + "B";
    }
}
