package com.appliedenhancements.util;

/** Saturating arithmetic for non-negative crafting amounts. */
public final class SaturatingLongMath {
    private SaturatingLongMath() {
    }

    public static long add(long left, long right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("Crafting amounts must be non-negative");
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public static long multiply(long left, long right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("Crafting amounts must be non-negative");
        }
        if (left == 0 || right == 0) {
            return 0;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
