package com.github.appliedenhancements.crafting.aelis;

import java.math.BigInteger;

/** Exact non-negative arithmetic used by the optional AELIS BigInteger path. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class AelisBigIntegerMath {
    static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private AelisBigIntegerMath() {
    }

    static BigInteger multiply(long left, long right) {
        requireNonNegative(left);
        requireNonNegative(right);
        if (left == 0 || right == 0) return BigInteger.ZERO;
        if (left <= Long.MAX_VALUE / right) return BigInteger.valueOf(left * right);
        return BigInteger.valueOf(left).multiply(BigInteger.valueOf(right));
    }

    static BigInteger multiply(long left, BigInteger right) {
        requireNonNegative(left);
        requireNonNegative(right);
        if (left == 0 || right.signum() == 0) return BigInteger.ZERO;
        if (left == 1) return right;
        if (right.bitLength() <= 63 && right.longValue() <= Long.MAX_VALUE / left) {
            return BigInteger.valueOf(left * right.longValue());
        }
        return BigInteger.valueOf(left).multiply(right);
    }

    public static BigInteger ceilDiv(BigInteger value, long divisor) {
        requireNonNegative(value);
        if (divisor <= 0) {
            throw new ArithmeticException("divisor must be positive");
        }
        if (divisor == 1) return value;
        if (value.bitLength() <= 63) {
            long small = value.longValue();
            return BigInteger.valueOf(small / divisor + (small % divisor == 0 ? 0 : 1));
        }
        BigInteger[] division = value.divideAndRemainder(BigInteger.valueOf(divisor));
        return division[1].signum() == 0 ? division[0] : division[0].add(BigInteger.ONE);
    }

    static long longValueExact(BigInteger value) {
        requireNonNegative(value);
        return value.longValueExact();
    }

    public static long saturatingLong(BigInteger value) {
        requireNonNegative(value);
        return value.compareTo(LONG_MAX) >= 0 ? Long.MAX_VALUE : value.longValueExact();
    }

    static long projectedAddition(BigInteger value, long current) {
        requireNonNegative(value);
        requireNonNegative(current);
        long remaining = Long.MAX_VALUE - current;
        return value.compareTo(BigInteger.valueOf(remaining)) >= 0
                ? remaining
                : value.longValueExact();
    }

    private static void requireNonNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
    }

    private static void requireNonNegative(BigInteger value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
    }
}
