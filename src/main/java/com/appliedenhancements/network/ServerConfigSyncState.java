package com.appliedenhancements.network;

import com.appliedenhancements.Config;
import com.github.appliedenhancements.config.AppliedEnhancementsConfig;
import org.jetbrains.annotations.ApiStatus;

/** Client view of server-authoritative settings used by client-side menus. */
@ApiStatus.Internal
public final class ServerConfigSyncState {
    private static volatile Values synchronizedValues;

    private ServerConfigSyncState() {
    }

    public static boolean isLongRangeCraftingEnabled() {
        return current().longRangeCraftingEnabled();
    }

    public static long getMaxCraftingOrderAmount() {
        return current().maxCraftingOrderAmount();
    }

    public static boolean isProgressDisplayEnabled() {
        return current().progressDisplayEnabled();
    }

    static void accept(long maxCraftingOrderAmount, boolean longRangeCraftingEnabled,
            boolean progressDisplayEnabled) {
        synchronizedValues = new Values(
                maxCraftingOrderAmount,
                longRangeCraftingEnabled,
                progressDisplayEnabled);
    }

    static void reset() {
        synchronizedValues = null;
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
                AppliedEnhancementsConfig.COMMON.enableLongRangeCrafting.get(),
                AppliedEnhancementsConfig.COMMON.enableProgressDisplay.get());
    }

    record Values(long maxCraftingOrderAmount, boolean longRangeCraftingEnabled,
            boolean progressDisplayEnabled) {
        Values {
            if (maxCraftingOrderAmount <= 0) {
                throw new IllegalArgumentException("Maximum crafting order amount must be positive");
            }
        }
    }
}
