package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class AelisBigIntegerMathTest {
    @Test
    void preservesIntermediateDemandAboveLongMax() {
        BigInteger demand = AelisBigIntegerMath.multiply(Long.MAX_VALUE, 2);

        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), demand);
        assertEquals(
                new BigInteger("6148914691236517205"),
                AelisBigIntegerMath.ceilDiv(demand, 3));
    }

    @Test
    void mergesBranchesWithoutWrapping() {
        BigInteger merged = AelisBigIntegerMath.multiply(Long.MAX_VALUE, 2)
                .add(BigInteger.valueOf(Long.MAX_VALUE));

        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3)), merged);
        assertEquals(Long.MAX_VALUE, AelisBigIntegerMath.saturatingLong(merged));
    }

    @Test
    void rejectsUnrepresentableAe2ExecutionCount() {
        BigInteger tooManyFirings = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

        assertThrows(ArithmeticException.class,
                () -> AelisBigIntegerMath.longValueExact(tooManyFirings));
    }

    @Test
    void projectsUnrepresentablePatternCountsWithoutWrapping() {
        BigInteger exact = BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.TEN);

        assertEquals(Long.MAX_VALUE,
                AelisBigIntegerMath.projectedAddition(exact, 0));
        assertEquals(7,
                AelisBigIntegerMath.projectedAddition(exact, Long.MAX_VALUE - 7));
        assertEquals(0,
                AelisBigIntegerMath.projectedAddition(exact, Long.MAX_VALUE));
        assertEquals(12,
                AelisBigIntegerMath.projectedAddition(BigInteger.valueOf(12), 5));
    }
}
