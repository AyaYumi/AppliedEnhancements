package com.appliedenhancements;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/** Quantity input modes; submission does not choose or manage a planner. */
public enum CraftingOrderMode implements TranslatableEnum {
    /** Leave AE2's native crafting order handling untouched. */
    DISABLED(false),
    /** Former long-valued mode up to {@link Long#MAX_VALUE}. */
    LONG_MAX(true),
    /** Former exact BigInteger order-entry mode. */
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
