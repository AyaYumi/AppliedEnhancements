package com.appliedenhancements.storage;

/**
 * Sentinel and arithmetic helpers for explicitly marked infinite storage cells.
 */
public final class InfiniteStorageAmounts {
    public static final long DISPLAY_AMOUNT = Long.MAX_VALUE;
    public static final String DISPLAY_TEXT = "9.2E";
    static final long MAX_FINITE_AMOUNT = Long.MAX_VALUE - 1;

    private InfiniteStorageAmounts() {
    }

    public static long mergeAvailable(long currentAmount, long additionalAmount, boolean infinite) {
        if (currentAmount < 0 || additionalAmount < 0) {
            throw new IllegalArgumentException("Available storage amounts must be non-negative");
        }
        if (infinite || currentAmount == DISPLAY_AMOUNT) {
            return DISPLAY_AMOUNT;
        }
        if (additionalAmount >= DISPLAY_AMOUNT
                || currentAmount > MAX_FINITE_AMOUNT - additionalAmount) {
            return MAX_FINITE_AMOUNT;
        }
        return currentAmount + additionalAmount;
    }
}
