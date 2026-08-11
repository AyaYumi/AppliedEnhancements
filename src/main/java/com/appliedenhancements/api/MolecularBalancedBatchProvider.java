package com.appliedenhancements.api;

import appeng.api.stacks.KeyCounter;

/**
 * Public API for AE2 crafting providers that need an explicit scheduling-batch boundary.
 *
 * <p>During one AE2 crafting-CPU scheduling pass, the first call to
 * {@code ICraftingProvider.pushPattern} for an implementing provider is preceded by
 * {@link #appliedenhancements$beginAdaptiveBatch(KeyCounter[])}. Every later push to the
 * same provider in that pass belongs to the same batch. The batch is closed exactly once
 * with {@link #appliedenhancements$endBalancedBatch()}, including when a push or the
 * surrounding scheduling pass fails.</p>
 *
 * <p>A scheduling batch may contain one or more push attempts, and an attempted push may
 * still be rejected by the provider. The {@code firstInputs} argument is a defensive
 * snapshot of the first attempted pattern inputs; subsequent inputs arrive through the
 * provider's normal {@code pushPattern} calls.</p>
 *
 * Example implementation:
 * <pre>
 * public class MyPatternProvider implements MolecularBalancedBatchProvider {
 *     private KeyCounter[] currentBatch;
 *
 *     {@literal @}Override
 *     public void appliedenhancements$beginBalancedBatch(KeyCounter[] firstInputs) {
 *         this.currentBatch = firstInputs;
 *         // Start batch processing
 *     }
 *
 *     {@literal @}Override
 *     public void appliedenhancements$endBalancedBatch() {
 *         // Finalize batch processing
 *         this.currentBatch = null;
 *     }
 * }
 * </pre>
 */
public interface MolecularBalancedBatchProvider {
    /**
     * Called before the first pattern push in a scheduling batch.
     * The provider should prepare any per-batch queue or transaction state here.
     *
     * @param firstInputs a defensive snapshot of the first attempted pattern inputs
     */
    void appliedenhancements$beginBalancedBatch(KeyCounter[] firstInputs);

    /**
     * Entry point used by Applied Enhancements. Defaults to the original balanced-batch
     * callback so existing implementations remain source and binary compatible.
     *
     * @param firstInputs a defensive snapshot of the first attempted pattern inputs
     */
    default void appliedenhancements$beginAdaptiveBatch(KeyCounter[] firstInputs) {
        appliedenhancements$beginBalancedBatch(firstInputs);
    }

    /**
     * Called after the scheduling batch ends. Implementations should flush or roll back
     * per-batch state and return to normal operation. This callback is paired with every
     * successful begin callback even when scheduling exits exceptionally.
     */
    void appliedenhancements$endBalancedBatch();
}
