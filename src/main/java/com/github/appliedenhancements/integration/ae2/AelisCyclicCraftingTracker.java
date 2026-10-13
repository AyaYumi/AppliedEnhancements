package com.github.appliedenhancements.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import com.github.appliedenhancements.crafting.aelis.AelisBigIntegerMath;
import com.github.appliedenhancements.crafting.aelis.AelisCyclicCraftingAmounts;
import java.math.BigInteger;
import java.util.Map;

public interface AelisCyclicCraftingTracker {
    void appliedenhancements$recordCyclicCrafting(
            IPatternDetails pattern, long patternTimes);

    default void appliedenhancements$recordCyclicCrafting(IPatternDetails pattern, BigInteger patternTimes) {
        appliedenhancements$recordCyclicCrafting(pattern, AelisBigIntegerMath.saturatingLong(patternTimes));
    }

    void appliedenhancements$mergeCyclicCraftAmounts(Map<AEKey, Long> amounts);

    Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts();

    default Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCyclicCraftAmounts() {
        return AelisCyclicCraftingAmounts.exactAmounts(appliedenhancements$getCyclicCraftAmounts(), Map.of());
    }

    default void appliedenhancements$mergeBigIntegerCyclicCraftAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$mergeCyclicCraftAmounts(AelisCyclicCraftingAmounts.project(amounts));
    }

    void appliedenhancements$recordCycleExecutionPlan(
            AelisCycleExecutionPlan plan);

    AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan();
}
