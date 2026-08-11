package com.appliedenhancements.crafting;

/**
 * Configuration mode for the crafting plan optimizer.
 */
public enum CraftingPlanMode {
    /**
     * Optimizer is disabled, use AE2 native planning.
     */
    OFF,

    /**
     * Safe mode: only optimize deterministic patterns.
     * Falls back to AE2 for patterns with:
     * - Container items (reusable tools)
     * - Multiple recipe candidates
     * - Substitution enabled
     * - Non-standard pattern types
     */
    SAFE
}
