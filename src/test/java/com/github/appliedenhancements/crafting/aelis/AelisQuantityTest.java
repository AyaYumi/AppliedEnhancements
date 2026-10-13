package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AelisQuantityTest {
    @Test void promotesOnOverflowAndNormalizesAfterDivisionOrSubtraction() {
        var maximum = AelisQuantity.of(Long.MAX_VALUE);
        assertTrue(maximum.isSmall());
        var promoted = maximum.add(AelisQuantity.of(1));
        assertFalse(promoted.isSmall());
        assertEquals(BigInteger.ONE.shiftLeft(63), promoted.toBigInteger());
        assertEquals(Long.MAX_VALUE, promoted.saturatingLong());
        assertThrows(ArithmeticException.class, promoted::longValueExact);
        assertTrue(promoted.subtract(1).isSmall());
        assertEquals(Long.MAX_VALUE, promoted.subtract(1).longValueExact());
        assertTrue(promoted.ceilDiv(2).isSmall());
        assertEquals(1L << 62, promoted.ceilDiv(2).longValueExact());
        assertEquals(Long.MAX_VALUE, maximum.ceilDiv(1).longValueExact());
    }

    @Test void matchesBigIntegerAcrossSmallAndArbitraryPrecisionCalculations() {
        var random = new Random(8173);
        for (int trial = 0; trial < 400; trial++) {
            BigInteger value = new BigInteger(trial % 129, random);
            long factor = trial % 3 == 0 ? Long.MAX_VALUE : random.nextInt(100) + 1;
            var quantity = AelisQuantity.of(value).multiply(factor);
            BigInteger expected = value.multiply(BigInteger.valueOf(factor));
            assertEquals(expected, quantity.toBigInteger());
            var extra = AelisQuantity.of(new BigInteger(70, random));
            assertEquals(expected, quantity.add(extra).subtract(extra).toBigInteger());
            long divisor = random.nextInt(1000) + 1;
            assertEquals(expected.add(BigInteger.valueOf(divisor - 1))
                    .divide(BigInteger.valueOf(divisor)), quantity.ceilDiv(divisor).toBigInteger());
            assertEquals(expected.bitLength() <= 63, quantity.isSmall());
        }
    }
}
