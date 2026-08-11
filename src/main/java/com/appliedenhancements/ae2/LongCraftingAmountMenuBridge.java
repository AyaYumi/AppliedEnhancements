package com.appliedenhancements.ae2;

import appeng.api.stacks.AEKey;

/**
 * Bridge interface for AE2 CraftAmountMenu to support long-range crafting orders.
 *
 * This interface is implemented via Mixin onto AE2's CraftAmountMenu to allow
 * crafting orders that exceed Integer.MAX_VALUE (2,147,483,647).
 */
public interface LongCraftingAmountMenuBridge {
    /**
     * Sets what to craft with a long initial amount.
     *
     * @param whatToCraft The item/fluid to craft
     * @param initialAmount The initial amount (can exceed int range)
     */
    void appliedenhancements$setWhatToCraftLong(AEKey whatToCraft, long initialAmount);

    /**
     * Confirms a long-range crafting order.
     *
     * @param amount The amount to craft (can exceed int range)
     * @param craftMissingAmount Whether to craft only missing amount
     * @param autoStart Whether to auto-start the crafting job
     */
    void appliedenhancements$confirmLong(long amount, boolean craftMissingAmount, boolean autoStart);
}
