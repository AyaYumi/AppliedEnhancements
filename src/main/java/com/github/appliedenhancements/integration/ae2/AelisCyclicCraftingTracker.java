package com.github.appliedenhancements.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import java.util.Map;

public interface AelisCyclicCraftingTracker {
    void appliedenhancements$recordCyclicCrafting(
            IPatternDetails pattern, long patternTimes);

    void appliedenhancements$mergeCyclicCraftAmounts(Map<AEKey, Long> amounts);

    Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts();

    void appliedenhancements$recordCycleExecutionPlan(
            AelisCycleExecutionPlan plan);

    AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan();
}
