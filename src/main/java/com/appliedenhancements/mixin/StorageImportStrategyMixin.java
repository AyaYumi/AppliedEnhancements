package com.appliedenhancements.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.appliedenhancements.Config;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.storage.ExternalStorageFacade;
import appeng.parts.automation.HandlerStrategy;
import appeng.parts.automation.StorageImportStrategy;

/** Reuses import facades and avoids zero-amount external extraction probes. */
@Mixin(value = StorageImportStrategy.class, remap = false)
public abstract class StorageImportStrategyMixin {
    @Unique
    private Object appliedenhancements$cachedHandler;

    @Unique
    private ExternalStorageFacade appliedenhancements$cachedFacade;

    @WrapOperation(method = "transfer", at = @At(value = "INVOKE",
            target = "Lappeng/parts/automation/HandlerStrategy;getFacade(Ljava/lang/Object;)Lappeng/me/storage/ExternalStorageFacade;"))
    private ExternalStorageFacade appliedenhancements$reuseExternalFacade(
            HandlerStrategy<?, ?> strategy,
            Object handler,
            Operation<ExternalStorageFacade> original) {
        if (!Config.ENABLE_IO_BUS_OPTIMIZATION.get()) {
            appliedenhancements$cachedHandler = null;
            appliedenhancements$cachedFacade = null;
            return original.call(strategy, handler);
        }

        if (handler != appliedenhancements$cachedHandler || appliedenhancements$cachedFacade == null) {
            appliedenhancements$cachedHandler = handler;
            appliedenhancements$cachedFacade = original.call(strategy, handler);
        }
        return appliedenhancements$cachedFacade;
    }

    @WrapOperation(method = "transfer", at = @At(value = "INVOKE",
            target = "Lappeng/me/storage/ExternalStorageFacade;extract(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J"))
    private long appliedenhancements$skipZeroAmountExtraction(
            ExternalStorageFacade storage,
            AEKey what,
            long amount,
            Actionable mode,
            IActionSource source,
            Operation<Long> original) {
        if (Config.ENABLE_IO_BUS_OPTIMIZATION.get() && amount <= 0) {
            return 0;
        }
        return original.call(storage, what, amount, mode, source);
    }
}
