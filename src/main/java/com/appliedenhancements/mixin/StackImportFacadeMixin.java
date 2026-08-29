package com.appliedenhancements.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.appliedenhancements.Config;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.behaviors.StackImportStrategy;
import appeng.api.behaviors.StackTransferContext;
import appeng.parts.automation.StackImportFacade;

/** Stops probing other key-space strategies after an import bus spends its operation budget. */
@Mixin(value = StackImportFacade.class, remap = false)
public abstract class StackImportFacadeMixin {
    @WrapOperation(method = "transfer", at = @At(value = "INVOKE",
            target = "Lappeng/api/behaviors/StackImportStrategy;transfer(Lappeng/api/behaviors/StackTransferContext;)Z"))
    private boolean appliedenhancements$stopAfterOperationBudget(
            StackImportStrategy strategy,
            StackTransferContext context,
            Operation<Boolean> original) {
        if (Config.ENABLE_IO_BUS_OPTIMIZATION.get() && !context.hasOperationsLeft()) {
            return true;
        }
        return original.call(strategy, context);
    }
}
