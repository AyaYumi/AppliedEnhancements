package com.appliedenhancements.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class AmountFormatterTest {
    @Test
    void hoverRetainsEveryDigitAndFluidFraction() {
        assertEquals("123,456,789,012,345,678,901,234,567,890,123,456,789",
                AmountFormatter.formatFull(new BigInteger("123456789012345678901234567890123456789"), 1));
        assertEquals("123,456,789,012,345,678.901", AmountFormatter.formatFull(new BigInteger("123456789012345678901"), 1000));
        assertEquals("1 / 3", AmountFormatter.formatFull(BigInteger.ONE, 3));
    }
    @Test
    void formatsLongAmountsWithoutFloatingPointRoundingDrift() {
        assertEquals("999", AmountFormatter.format(999));
        assertEquals("1K", AmountFormatter.format(1_000));
        assertEquals("1.5M", AmountFormatter.format(1_500_000));
        assertEquals("9.2E", AmountFormatter.format(Long.MAX_VALUE));
        assertEquals("-9.2E", AmountFormatter.format(Long.MIN_VALUE));
        assertEquals("1.5K", AmountFormatter.format(new BigDecimal("1500.5")));
    }

    @Test
    void keepsDataEnergisticsUnitsBeyondExa() {
        assertEquals("1Z", AmountFormatter.format(BigInteger.TEN.pow(21)));
        assertEquals("1Y", AmountFormatter.format(BigInteger.TEN.pow(24)));
        assertEquals("1B", AmountFormatter.format(BigInteger.TEN.pow(27)));
        assertEquals("1Att", AmountFormatter.format(BigInteger.TEN.pow(114)));
        assertEquals("1.00E117", AmountFormatter.format(BigInteger.TEN.pow(117)));
    }
}
