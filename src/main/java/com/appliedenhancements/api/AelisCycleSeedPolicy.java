package com.appliedenhancements.api;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

public enum AelisCycleSeedPolicy implements TranslatableEnum {
    PRESERVE_MINIMUM,
    MAX_THROUGHPUT;

    @Override
    public Component getTranslatedName() {
        return Component.translatable(switch (this) {
            case PRESERVE_MINIMUM -> "appliedenhancements.config.cycle_seed_policy.preserve_minimum";
            case MAX_THROUGHPUT -> "appliedenhancements.config.cycle_seed_policy.max_throughput";
        });
    }
}
