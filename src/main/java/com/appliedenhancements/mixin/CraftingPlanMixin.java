package com.appliedenhancements.mixin;

import appeng.crafting.CraftingPlan;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPath;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPathCarrier;
import java.util.Objects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = CraftingPlan.class, remap = false)
public abstract class CraftingPlanMixin implements OmniCalculationPathCarrier {
    @Unique
    private OmniCalculationPath appliedenhancements$calculationPath =
            OmniCalculationPath.AE2_NATIVE;

    @Override
    public OmniCalculationPath molecularmanipulator$getCalculationPath() {
        return appliedenhancements$calculationPath;
    }

    @Override
    public void molecularmanipulator$setCalculationPath(OmniCalculationPath path) {
        appliedenhancements$calculationPath = Objects.requireNonNull(path, "path");
    }
}
