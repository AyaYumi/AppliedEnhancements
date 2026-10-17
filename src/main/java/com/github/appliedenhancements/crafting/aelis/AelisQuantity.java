package com.github.appliedenhancements.crafting.aelis;

import java.math.BigInteger;

/** Non-negative exact quantity. Ordinary arithmetic stays in long until promotion is necessary. */
final class AelisQuantity {
    static final AelisQuantity ZERO = new AelisQuantity(0, null);
    private final long small;
    private final BigInteger large;

    private AelisQuantity(long small, BigInteger large) {
        this.small = small;
        this.large = large;
    }

    static AelisQuantity of(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative quantity");
        return value == 0 ? ZERO : new AelisQuantity(value, null);
    }

    static AelisQuantity of(BigInteger value) {
        if (value.signum() < 0) throw new IllegalArgumentException("Negative quantity");
        return value.bitLength() <= 63 ? of(value.longValue()) : new AelisQuantity(0, value);
    }

    int signum() { return large == null ? Long.compare(small, 0) : 1; }
    boolean isSmall() { return large == null; }
    BigInteger toBigInteger() { return large == null ? BigInteger.valueOf(small) : large; }
    long saturatingLong() { return large == null ? small : Long.MAX_VALUE; }
    long longValueExact() { return large == null ? small : large.longValueExact(); }

    AelisQuantity multiply(long multiplier) {
        if (multiplier < 0) throw new IllegalArgumentException("Negative multiplier");
        if (multiplier == 0 || signum() == 0) return ZERO;
        if (multiplier == 1) return this;
        if (large == null && small <= Long.MAX_VALUE / multiplier) return of(small * multiplier);
        return of(toBigInteger().multiply(BigInteger.valueOf(multiplier)));
    }

    AelisQuantity add(AelisQuantity other) {
        if (other.signum() == 0) return this;
        if (signum() == 0) return other;
        if (large == null && other.large == null && small <= Long.MAX_VALUE - other.small) {
            return of(small + other.small);
        }
        return of(toBigInteger().add(other.toBigInteger()));
    }

    AelisQuantity subtract(long value) { return subtract(of(value)); }

    AelisQuantity subtract(AelisQuantity other) {
        if (other.signum() == 0) return this;
        if (large == null && other.large == null) return of(small - other.small);
        return of(toBigInteger().subtract(other.toBigInteger()));
    }

    AelisQuantity ceilDiv(long divisor) {
        if (divisor <= 0) throw new ArithmeticException("Non-positive divisor");
        if (divisor == 1) return this;
        if (large == null) return of(small / divisor + (small % divisor == 0 ? 0 : 1));
        return of(AelisBigIntegerMath.ceilDiv(large, divisor));
    }
}
