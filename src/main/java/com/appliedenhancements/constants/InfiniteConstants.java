package com.appliedenhancements.constants;

/**
 * Constants for infinite storage and parallelism display.
 *
 * These values are sentinel markers used by AE2 to indicate infinite/unlimited capacity.
 */
public final class InfiniteConstants {
    /**
     * Sentinel value indicating infinite storage capacity.
     * Used by creative storage cells and other unlimited storage devices.
     */
    public static final long INFINITE_STORAGE = Long.MAX_VALUE;

    /**
     * Sentinel value indicating infinite co-processor parallelism.
     * Used by special crafting CPUs with unlimited parallel processing.
     */
    public static final int INFINITE_PARALLELISM = Integer.MAX_VALUE;

    private InfiniteConstants() {
    }
}
