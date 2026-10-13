package com.appliedenhancements.client;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import com.appliedenhancements.util.AmountFormatter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/** Compact rows and full hover text share exact quantities and the key's display unit. */
public final class CraftingPlanAmountFormatter {
    private CraftingPlanAmountFormatter() {}

    public static String format(AEKey key, BigInteger amount, AmountFormat format) {
        String text = format == AmountFormat.FULL
                ? AmountFormatter.formatFull(amount, key.getAmountPerUnit())
                : AmountFormatter.format(new BigDecimal(amount).divide(
                        BigDecimal.valueOf(key.getAmountPerUnit()), MathContext.DECIMAL128));
        String unit = key.getUnitSymbol();
        return format == AmountFormat.FULL && unit != null ? text + " " + unit : text;
    }
}
