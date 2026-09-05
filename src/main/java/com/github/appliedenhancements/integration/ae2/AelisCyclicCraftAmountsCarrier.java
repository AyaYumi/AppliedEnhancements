package com.github.appliedenhancements.integration.ae2;

import appeng.api.stacks.AEKey;
import java.util.Map;

public interface AelisCyclicCraftAmountsCarrier {
    Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts();

    void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> amounts);
}
