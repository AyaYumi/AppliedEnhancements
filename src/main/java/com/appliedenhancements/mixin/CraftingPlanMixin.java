package com.appliedenhancements.mixin;

import appeng.crafting.CraftingPlan;
import appeng.api.stacks.AEKey;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCycleExecutionPlanCarrier;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import java.util.Map;
import java.util.Objects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = CraftingPlan.class, remap = false)
public abstract class CraftingPlanMixin implements AelisCalculationPathCarrier,
        AelisCyclicCraftAmountsCarrier, AelisCycleExecutionPlanCarrier {
    @Unique
    private AelisCalculationPath appliedenhancements$calculationPath =
            AelisCalculationPath.AE2_NATIVE;
    @Unique
    private Map<AEKey, Long> appliedenhancements$cyclicCraftAmounts = Map.of();
    @Unique
    private AelisCycleExecutionPlan appliedenhancements$cycleExecutionPlan;

    @Override
    public AelisCalculationPath molecularmanipulator$getCalculationPath() {
        return appliedenhancements$calculationPath;
    }

    @Override
    public void molecularmanipulator$setCalculationPath(AelisCalculationPath path) {
        appliedenhancements$calculationPath = Objects.requireNonNull(path, "path");
    }

    @Override
    public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() {
        return appliedenhancements$cyclicCraftAmounts;
    }

    @Override
    public void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> amounts) {
        appliedenhancements$cyclicCraftAmounts = Map.copyOf(
                Objects.requireNonNull(amounts, "amounts"));
    }

    @Override
    public AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan() {
        return appliedenhancements$cycleExecutionPlan;
    }

    @Override
    public void appliedenhancements$setCycleExecutionPlan(
            AelisCycleExecutionPlan plan) {
        appliedenhancements$cycleExecutionPlan = plan;
    }
}
