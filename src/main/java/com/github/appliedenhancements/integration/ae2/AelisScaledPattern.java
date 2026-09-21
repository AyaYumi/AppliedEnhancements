package com.github.appliedenhancements.integration.ae2;

import appeng.api.crafting.IPatternDetails;

/** Access to a quantity-only pattern wrapper for cyclic recovery and exact task reconciliation.
 * The multiplier is relative to the returned original, and all inputs/outputs must share that factor.
 */
public interface AelisScaledPattern {
    IPatternDetails appliedenhancements$originalPattern();
    long appliedenhancements$operationsPerPush();
}
