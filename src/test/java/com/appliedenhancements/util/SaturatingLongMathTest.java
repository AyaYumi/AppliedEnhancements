package com.appliedenhancements.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SaturatingLongMathTest {
    @Test
    void addsWithinRange() {
        assertEquals(5, SaturatingLongMath.add(2, 3));
    }

    @Test
    void saturatesAdditionAtLongMaximum() {
        assertEquals(Long.MAX_VALUE, SaturatingLongMath.add(Long.MAX_VALUE, 1));
    }

    @Test
    void saturatesMultiplicationAtLongMaximum() {
        assertEquals(Long.MAX_VALUE, SaturatingLongMath.multiply(Long.MAX_VALUE, 2));
        assertEquals(42, SaturatingLongMath.multiply(6, 7));
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> SaturatingLongMath.add(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> SaturatingLongMath.multiply(1, -1));
    }
}
