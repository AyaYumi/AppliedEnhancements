package com.appliedenhancements.api;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Objects;

/** Request shared by exact planning entry points; non-positive amounts are empty orders. */
public record AelisCraftingRequest(
        AEKey output,
        BigInteger amount,
        CalculationStrategy strategy) {
    public AelisCraftingRequest {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(strategy, "strategy");
        amount = new AelisExactRequest(amount).amount();
    }

    public static AelisCraftingRequest of(AEKey output, long amount) {
        return of(output, BigInteger.valueOf(amount));
    }

    public static AelisCraftingRequest of(AEKey output, BigInteger amount) {
        return new AelisCraftingRequest(output, amount,
                CalculationStrategy.REPORT_MISSING_ITEMS);
    }
}
