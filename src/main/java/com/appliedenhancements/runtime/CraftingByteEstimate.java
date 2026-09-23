package com.appliedenhancements.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact fractional byte estimate. Round up only when the whole plan is finalized. */
public record CraftingByteEstimate(BigInteger numerator, BigInteger denominator) {
    public static final CraftingByteEstimate ZERO = new CraftingByteEstimate(BigInteger.ZERO, BigInteger.ONE);

    public CraftingByteEstimate {
        if (numerator.signum() < 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("Crafting byte estimates must be non-negative");
        }
        var gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
    }

    public static CraftingByteEstimate fromDouble(double value) {
        var decimal = BigDecimal.valueOf(value);
        int scale = decimal.scale();
        return scale < 0
                ? new CraftingByteEstimate(decimal.unscaledValue().multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE)
                : new CraftingByteEstimate(decimal.unscaledValue(), BigInteger.TEN.pow(scale));
    }

    public CraftingByteEstimate add(CraftingByteEstimate other) {
        var common = denominator.gcd(other.denominator);
        var left = other.denominator.divide(common);
        var right = denominator.divide(common);
        return new CraftingByteEstimate(numerator.multiply(left).add(other.numerator.multiply(right)),
                denominator.multiply(left));
    }

    public BigInteger ceil() {
        var divided = numerator.divideAndRemainder(denominator);
        return divided[1].signum() == 0 ? divided[0] : divided[0].add(BigInteger.ONE);
    }

    public double projection() {
        return Math.min(Double.MAX_VALUE, new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), java.math.MathContext.DECIMAL64).doubleValue());
    }
}
