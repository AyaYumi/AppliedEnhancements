package com.appliedenhancements.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ExactLongValueParserTest {
    @Test
    void acceptsLongMaximumExactly() {
        assertEquals(
                Long.MAX_VALUE,
                parse(Long.toString(Long.MAX_VALUE), 1).orElseThrow());
        assertEquals(
                Long.MAX_VALUE,
                parse("=" + Long.MAX_VALUE, 1).orElseThrow());
    }

    @Test
    void rejectsValuesThatWouldWrapThroughLongValue() {
        assertTrue(parse("18446744073709551617", 1).isEmpty());
        assertTrue(parse("9223372036854775807+1", 1).isEmpty());
    }

    @Test
    void rejectsFractionalIntegralAmounts() {
        assertTrue(parse("1.5", 1).isEmpty());
    }

    @Test
    void convertsScaledUnitsExactly() {
        assertEquals(1_250L, parse("1.25", 1_000).orElseThrow());
    }

    @Test
    void doesNotRoundAnOverflowingDecimalBackIntoLongRange() {
        assertTrue(parse("922337203685477580.70000000000000000001", 10).isEmpty());
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ExactLongValueParser.parse("1", decimalFormat(), 1, 2, 1));
    }

    private static java.util.OptionalLong parse(String expression, long amountPerUnit) {
        return ExactLongValueParser.parse(
                expression,
                decimalFormat(),
                amountPerUnit,
                1,
                Long.MAX_VALUE);
    }

    private static DecimalFormat decimalFormat() {
        var format = new DecimalFormat("#.######", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setParseBigDecimal(true);
        format.setNegativePrefix("-");
        return format;
    }
}
