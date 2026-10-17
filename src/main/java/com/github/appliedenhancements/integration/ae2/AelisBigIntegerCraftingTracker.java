package com.github.appliedenhancements.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;

public interface AelisBigIntegerCraftingTracker extends AelisBigIntegerCraftAmountsCarrier {
    void appliedenhancements$recordBigIntegerCrafting(
            IPatternDetails pattern, long patternTimes);

    void appliedenhancements$recordBigIntegerCrafting(
            IPatternDetails pattern, BigInteger patternTimes);

    void appliedenhancements$beginProjectedCraftingTransfer();

    void appliedenhancements$endProjectedCraftingTransfer();

    void appliedenhancements$mergeBigIntegerCraftAmounts(
            Map<AEKey, BigInteger> amounts);

    /** Append inside the owning simulation transaction without exposing its mutable ledger. */
    default void appliedenhancements$recordBigIntegerMissing(AEKey key, BigInteger amount) {
        var missing = new java.util.LinkedHashMap<>(appliedenhancements$getBigIntegerMissingAmounts());
        missing.merge(key, amount, BigInteger::add);
        appliedenhancements$setBigIntegerMissingAmounts(missing);
    }
}
