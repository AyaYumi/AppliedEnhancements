package com.appliedenhancements.api;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Objects;

/** Validated request shared by exact planning entry points. */
public record AelisCraftingRequest(
        AEKey output,
        BigInteger amount,
        CalculationStrategy strategy) {
    public AelisCraftingRequest {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(strategy, "strategy");
        new AelisExactRequest(amount);
        // AE2's CRAFT_LESS search varies long attempt amounts. The exact scope
        // currently represents one fixed order, so do not silently ignore that search.
        if (strategy != CalculationStrategy.REPORT_MISSING_ITEMS) {
            throw new IllegalArgumentException("Exact requests currently support REPORT_MISSING_ITEMS only");
        }
    }

    public static AelisCraftingRequest of(AEKey output, long amount) {
        return of(output, BigInteger.valueOf(amount));
    }

    public static AelisCraftingRequest of(AEKey output, BigInteger amount) {
        return new AelisCraftingRequest(output, amount,
                CalculationStrategy.REPORT_MISSING_ITEMS);
    }
}
