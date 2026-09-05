package com.github.appliedenhancements.integration.ae2;

import appeng.api.crafting.IPatternDetails;

/** Access to a quantity-only pattern wrapper when recovering legacy cyclic jobs. */
public interface AelisScaledPattern {
    IPatternDetails appliedenhancements$originalPattern();
    long appliedenhancements$operationsPerPush();
}
