package com.appliedenhancements.mixin;

import java.util.ArrayDeque;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.appliedenhancements.runtime.MolecularBalancedBatchScope;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.service.CraftingService;
import net.minecraft.world.level.Level;

/**
 * Brackets all pushes to an opted-in provider during one CPU scheduling pass.
 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicBatchMixin {
    @Unique
    private static final ThreadLocal<ArrayDeque<MolecularBalancedBatchScope>>
            APPLIEDENHANCEMENTS_BATCH_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    @WrapMethod(method = "executeCrafting")
    private int appliedenhancements$withBalancedBatchScope(int maxPatterns,
            CraftingService craftingService, IEnergyService energyService, Level level,
            Operation<Integer> original) {
        var scopes = APPLIEDENHANCEMENTS_BATCH_SCOPES.get();
        try (var scope = new MolecularBalancedBatchScope()) {
            scopes.push(scope);
            try {
                return original.call(maxPatterns, craftingService, energyService, level);
            } finally {
                MolecularBalancedBatchScope removed = scopes.pop();
                if (removed != scope) {
                    throw new IllegalStateException("Unbalanced crafting batch scope stack");
                }
                if (scopes.isEmpty()) {
                    APPLIEDENHANCEMENTS_BATCH_SCOPES.remove();
                }
            }
        }
    }

    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean appliedenhancements$openProviderBatch(ICraftingProvider provider,
            IPatternDetails details, KeyCounter[] inputHolder, Operation<Boolean> original) {
        var scopes = APPLIEDENHANCEMENTS_BATCH_SCOPES.get();
        if (!scopes.isEmpty()) {
            scopes.peek().beginIfNeeded(provider, inputHolder);
        }
        return original.call(provider, details, inputHolder);
    }
}
