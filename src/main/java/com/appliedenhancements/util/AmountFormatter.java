package com.appliedenhancements.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Decimal compact formatter compatible with DataEnergistics' extended units. */
public final class AmountFormatter {
    private static final BigInteger UNIT_BASE = BigInteger.valueOf(1_000L);
    private static final String[] COMPACT_UNITS = {
            "", "K", "M", "G", "T", "P", "E", "Z", "Y", "B", "N", "D", "C", "S", "O", "Q", "X", "W", "V",
            "U", "Tt", "Gt", "Mt", "St", "Ot", "Nt", "Dt", "Ct", "Lt", "Kt", "Jt", "It", "Ht", "Gtt", "Ett",
            "Dtt", "Ctt", "Btt", "Att"
    };
    private static final BigDecimal SCIENTIFIC_THRESHOLD =
            new BigDecimal(UNIT_BASE.pow(COMPACT_UNITS.length));

    private AmountFormatter() {
    }

    public static String format(long value) {
        return format(BigInteger.valueOf(value));
    }

    /** Full precision for hover text; never passes through double or DECIMAL128. */
    public static String formatFull(BigInteger value, long amountPerUnit) {
        BigDecimal units;
        try {
            units = new BigDecimal(value).divide(BigDecimal.valueOf(amountPerUnit));
        } catch (ArithmeticException repeatingUnit) {
            // Preserve the exact quantity even for third-party units such as 1/3.
            return decimalFormat("#,##0").format(value) + " / " + amountPerUnit;
        }
        var formatter = decimalFormat("#,##0");
        formatter.setMaximumFractionDigits(Math.max(0, units.scale()));
        return formatter.format(units);
    }

    public static String format(BigInteger value) {
        return formatParts(value).text();
    }

    public static String format(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        return formatParts(value).text();
    }

    public static FormattedAmount formatParts(BigInteger value) {
        if (value == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        return formatParts(new BigDecimal(value));
    }

    private static FormattedAmount formatParts(BigDecimal value) {
        if (value.signum() == 0) {
            return new FormattedAmount("0", "");
        }
        BigDecimal absolute = value.abs();
        if (absolute.compareTo(SCIENTIFIC_THRESHOLD) >= 0) {
            return new FormattedAmount(decimalFormat("0.00E00").format(value), "");
        }
        int unitIndex = Math.max(
                0,
                (absolute.precision() - absolute.scale() - 1) / 3);
        String digits = decimalFormat("#,##0.#")
                .format(absolute.movePointLeft(unitIndex * 3));
        return new FormattedAmount(value.signum() < 0 ? "-" + digits : digits,
                COMPACT_UNITS[unitIndex]);
    }

    private static DecimalFormat decimalFormat(String pattern) {
        var format = new DecimalFormat(
                pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format;
    }

    public record FormattedAmount(String digits, String unit) {
        public String text() {
            return digits + unit;
        }
    }
}
