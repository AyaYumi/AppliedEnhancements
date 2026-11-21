package com.appliedenhancements.mixin;

import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftingTracker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class AelisNativeMissingMixin {
    @WrapOperation(method = "request", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/CraftingCalculation;addMissing(Lappeng/api/stacks/AEKey;J)V"))
    private void appliedenhancements$stageNativeMissing(CraftingCalculation calculation,
            AEKey key, long amount, Operation<Void> original,
            @Local(argsOnly = true) CraftingSimulationState inventory) {
        if (CraftingPlannerIntervention.stagesMissing()
                && inventory instanceof AelisBigIntegerCraftingTracker tracker) {
            if (amount < 0) {
                throw new ArithmeticException("Native crafting shortfall overflow");
            }
            if (amount > 0) {
                tracker.appliedenhancements$recordBigIntegerMissing(key, BigInteger.valueOf(amount));
            }
        } else {
            original.call(calculation, key, amount);
        }
    }
}
