package com.appliedenhancements.api;

import net.minecraft.network.chat.Component;

public enum AelisCycleSeedPolicy {
    PRESERVE_MINIMUM,
    MAX_THROUGHPUT;

    /** Localized display name; {@link #name()} remains the stored TOML identifier. */
    public Component getTranslatedName() {
        return Component.translatable(switch (this) {
            case PRESERVE_MINIMUM -> "appliedenhancements.config.cycle_seed_policy.preserve_minimum";
            case MAX_THROUGHPUT -> "appliedenhancements.config.cycle_seed_policy.max_throughput";
        });
    }
}
