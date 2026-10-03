package com.appliedenhancements.runtime;

/** Saturated compatibility quantities for exact CPU accounting; never reject native plans. */
public final class CraftingAmountProjection {
    private CraftingAmountProjection() {}

    public static long saturatingAddNonNegative(long left, long right) {
        if (left < 0 || right < 0) throw new IllegalArgumentException("Projection operands must be non-negative");
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public static long saturatingMultiplyNonNegative(long left, long right) {
        if (left < 0 || right < 0) throw new IllegalArgumentException("Projection operands must be non-negative");
        return right != 0 && left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
