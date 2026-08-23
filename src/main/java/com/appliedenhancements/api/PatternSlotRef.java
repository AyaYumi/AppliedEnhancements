package com.appliedenhancements.api;

/**
 * Stable identity of one pattern slot in a pattern-access terminal.
 *
 * <p>The container id is the server id assigned by AE2's pattern terminal,
 * not a menu slot index. Instances are safe to retain only while the current
 * terminal menu remains open.</p>
 */
public record PatternSlotRef(long containerId, int slot) {
    public PatternSlotRef {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
    }
}
