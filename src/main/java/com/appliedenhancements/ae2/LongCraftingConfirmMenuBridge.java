package com.appliedenhancements.ae2;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;

/**
 * Bridge interface for AE2 CraftConfirmMenu to support long-range crafting plans.
 *
 * This interface is implemented via Mixin onto AE2's CraftConfirmMenu to allow
 * crafting calculations that exceed Integer.MAX_VALUE.
 */
public interface LongCraftingConfirmMenuBridge {
    /**
     * Plans a long-range crafting job.
     *
     * @param what The item/fluid to craft
     * @param amount The amount to craft (can exceed int range)
     * @param strategy The calculation strategy (report missing or craft less)
     * @return true if the planning job was started successfully
     */
    boolean appliedenhancements$planLong(AEKey what, long amount, CalculationStrategy strategy);
}
