package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import com.appliedenhancements.runtime.AelisCraftingPlanRewrite;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.Function;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.SmartDoublingPlans",
        remap = false)
public abstract class UselessSmartDoublingPlanMixin {
    @WrapMethod(method = "name=/^rewriteForSubmission$/ desc=/^\\(Lappeng\\/api\\/networking\\/crafting\\/ICraftingPlan;Ljava\\/util\\/function\\/Function;\\)Lappeng\\/api\\/networking\\/crafting\\/ICraftingPlan;$/", require = 0)
    private static ICraftingPlan appliedenhancements$preserveCyclicPlan(
            ICraftingPlan plan,
            Function<IPatternDetails, Iterable<ICraftingProvider>> providers,
            Operation<ICraftingPlan> original) {
        return AelisCraftingPlanRewrite.rewriteOrdinaryPatterns(
                plan, ordinary -> original.call(ordinary, providers));
    }
    @WrapMethod(method = "name=/^rewriteForSubmission$/ desc=/^\\(Lappeng\\/api\\/networking\\/crafting\\/ICraftingPlan;Ljava\\/util\\/function\\/Function;Lnet\\/minecraft\\/world\\/level\\/Level;\\)Lappeng\\/api\\/networking\\/crafting\\/ICraftingPlan;$/", require = 0)
    private static ICraftingPlan appliedenhancements$preserveNativePlan(
            ICraftingPlan plan, Function<IPatternDetails, Iterable<ICraftingProvider>> providers,
            net.minecraft.world.level.Level level, Operation<ICraftingPlan> original) {
        return AelisCraftingPlanRewrite.rewriteOrdinaryPatterns(
                plan, ordinary -> original.call(ordinary, providers, level));
    }
}
