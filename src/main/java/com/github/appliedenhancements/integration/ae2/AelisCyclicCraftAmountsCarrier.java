package com.github.appliedenhancements.integration.ae2;

import appeng.api.stacks.AEKey;
import com.github.appliedenhancements.crafting.aelis.AelisCyclicCraftingAmounts;
import java.math.BigInteger;
import java.util.Map;

public interface AelisCyclicCraftAmountsCarrier {
    Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts();

    void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> amounts);

    default Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCyclicCraftAmounts() {
        var amounts = appliedenhancements$getCyclicCraftAmounts();
        return amounts == null ? Map.of() : AelisCyclicCraftingAmounts.exactAmounts(amounts, Map.of());
    }

    /** Default preserves compatibility with integrations that only expose long quantities. */
    default void appliedenhancements$setBigIntegerCyclicCraftAmounts(Map<AEKey, BigInteger> amounts) {}
}
