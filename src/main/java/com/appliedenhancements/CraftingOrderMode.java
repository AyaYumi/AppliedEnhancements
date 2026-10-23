package com.appliedenhancements;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/** Controls whether Applied Enhancements takes over AE2 crafting order entry. */
public enum CraftingOrderMode implements TranslatableEnum {
    /** Leave AE2's native crafting order handling untouched. */
    DISABLED(false),
    /** Enable the long-valued path up to {@link Long#MAX_VALUE}. */
    LONG_MAX(true),
    /** Enable the exact BigInteger path in addition to the long-valued path. */
    BIG_INTEGER(true);

    private final boolean enabled;

    CraftingOrderMode(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(switch (this) {
            case DISABLED -> "appliedenhancements.config.max_crafting_order_amount.disabled";
            case LONG_MAX -> "appliedenhancements.config.max_crafting_order_amount.long_max";
            case BIG_INTEGER -> "appliedenhancements.config.max_crafting_order_amount.big_integer";
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean supportsBigInteger() {
        return this == BIG_INTEGER;
    }

    public int id() {
        return ordinal();
    }

    public static CraftingOrderMode fromId(int id) {
        CraftingOrderMode[] values = values();
        if (id < 0 || id >= values.length) {
            throw new IllegalArgumentException("Unknown crafting order mode id: " + id);
        }
        return values[id];
    }

    /** Converts the pre-1.0.10 boolean and numeric settings to the nearest mode. */
    public static CraftingOrderMode fromLegacy(
            boolean longRangeEnabled, long maximumAmount, boolean bigIntegerEnabled) {
        if (!longRangeEnabled) {
            return DISABLED;
        }
        return maximumAmount == Long.MAX_VALUE && bigIntegerEnabled
                ? BIG_INTEGER
                : LONG_MAX;
    }
}
