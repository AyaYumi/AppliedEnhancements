package com.appliedenhancements.network;

import com.appliedenhancements.Config;
import com.appliedenhancements.CraftingOrderMode;
import org.jetbrains.annotations.ApiStatus;

/** Client view of server-authoritative settings used by client-side menus. */
@ApiStatus.Internal
public final class ServerConfigSyncState {
    private static volatile Values synchronizedValues;
    private static volatile Boolean exactEnabled;
    public static boolean isBigIntegerEnabled() {
        return current().craftingOrderMode().supportsBigInteger();
    }

    private ServerConfigSyncState() {
    }

    public static boolean isLongRangeCraftingEnabled() {
        return current().craftingOrderMode().isEnabled();
    }

    public static long getMaxCraftingOrderAmount() {
        return Long.MAX_VALUE;
    }

    public static boolean isProgressDisplayEnabled() {
        return current().progressDisplayEnabled();
    }

    public static boolean isInfiniteStorageLimitBypassEnabled() {
        return current().infiniteStorageLimitBypassEnabled();
    }

    static void accept(CraftingOrderMode craftingOrderMode,
            boolean progressDisplayEnabled, boolean infiniteStorageLimitBypassEnabled,
            boolean bigIntegerEnabled) {
        synchronizedValues = new Values(
                craftingOrderMode,
                progressDisplayEnabled,
                infiniteStorageLimitBypassEnabled);
        exactEnabled = bigIntegerEnabled;
    }

    static void acceptExact(boolean enabled) {
        exactEnabled = enabled;
    }

    static void reset() {
        synchronizedValues = null;
        exactEnabled = null;
    }

    static Values currentOr(Values fallback) {
        Values synchronizedSnapshot = synchronizedValues;
        return synchronizedSnapshot != null ? synchronizedSnapshot : fallback;
    }

    private static Values current() {
        Values synchronizedSnapshot = synchronizedValues;
        if (synchronizedSnapshot != null) {
            return synchronizedSnapshot;
        }
        return new Values(
                Config.MAX_CRAFTING_ORDER_AMOUNT.get(),
                Config.ENABLE_PROGRESS_DISPLAY.get(),
                Config.ENABLE_INFINITE_STORAGE_LIMIT_BYPASS.get());
    }

    record Values(CraftingOrderMode craftingOrderMode,
            boolean progressDisplayEnabled, boolean infiniteStorageLimitBypassEnabled) {
        Values {
            if (craftingOrderMode == null) {
                throw new IllegalArgumentException("Crafting order mode must be present");
            }
        }
    }
}
