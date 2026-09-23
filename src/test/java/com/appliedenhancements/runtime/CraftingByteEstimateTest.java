package com.appliedenhancements.runtime;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftingByteEstimateTest {
    @Test void retainsLargeIntegersAndRoundsOnlyTheFinalSum() {
        var huge = BigInteger.TEN.pow(30).add(BigInteger.ONE);
        var whole = new CraftingByteEstimate(huge, BigInteger.ONE);
        var third = new CraftingByteEstimate(BigInteger.ONE, BigInteger.valueOf(3));
        assertEquals(huge, whole.ceil());
        assertEquals(huge.add(BigInteger.ONE), whole.add(third).add(third).add(third).ceil());
        assertEquals(BigInteger.ONE, third.add(third).ceil());
        assertEquals(BigInteger.ONE, CraftingByteEstimate.fromDouble(0.125)
                .add(CraftingByteEstimate.fromDouble(0.875)).ceil());
        assertEquals(BigInteger.valueOf(1_000), CraftingByteEstimate.fromDouble(1e3).ceil());
    }

    @Test void rejectsInvalidByteEstimatesAndBoundsCompatibilityProjection() {
        assertThrows(IllegalArgumentException.class, () -> new CraftingByteEstimate(BigInteger.ONE.negate(), BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> new CraftingByteEstimate(BigInteger.ONE, BigInteger.ZERO));
        assertEquals(Double.MAX_VALUE, new CraftingByteEstimate(BigInteger.TEN.pow(400), BigInteger.ONE).projection());
    }
}
