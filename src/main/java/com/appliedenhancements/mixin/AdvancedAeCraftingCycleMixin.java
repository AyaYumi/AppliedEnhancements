package com.appliedenhancements.mixin;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.appliedenhancements.runtime.AelisCycleDispatchScope;
import com.appliedenhancements.runtime.AelisCycleDispatch;
import com.appliedenhancements.runtime.AdvancedAeCycleRecovery;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.HolderLookup;
import appeng.me.service.CraftingService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
// Keep the guarded inventory outside batching wrappers, including their extractions after original.call.
@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic", remap = false, priority = 1100)
public abstract class AdvancedAeCraftingCycleMixin {
    @Shadow public abstract ListCraftingInventory getInventory();
    @Shadow public abstract GenericStack getFinalJobOutput();
    @Shadow public abstract boolean hasJob();
    @Shadow public abstract long getWaitingFor(AEKey key);
    @Shadow public abstract void cancel();
    @Shadow private void finishJob(boolean success) { throw new AssertionError(); }

    @Unique private AelisCycleRuntimeController appliedenhancements$cycleRuntime;
    @Unique private int appliedenhancements$dispatchDepth;
    @Unique private boolean appliedenhancements$retainingCycleOutput;
    @Unique private static final String APPLIEDENHANCEMENTS_CYCLE_TAG = "appliedenhancementsCycleRuntime";

    @ModifyVariable(method = "trySubmitJob", at = @At("HEAD"), argsOnly = true)
    private ICraftingPlan appliedenhancements$normalizeCyclePlan(ICraftingPlan plan) {
        return AelisCycleExecutionApi.preparePlan(plan);
    }

    @Inject(method = "trySubmitJob", at = @At("RETURN"))
    private void appliedenhancements$startCycle(IGrid grid, ICraftingPlan plan,
            IActionSource source, ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (callback.getReturnValue() != null && callback.getReturnValue().successful()) {
            appliedenhancements$cycleRuntime = AelisCycleExecutionApi.getPlan(plan)
                    .map(AelisCycleRuntimeController::withCyclePhase).orElse(null);
        }
    }

    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs(Lappeng/api/crafting/IPatternDetails;Lappeng/crafting/inv/ICraftingInventory;Lnet/minecraft/world/level/Level;Lappeng/api/stacks/KeyCounter;Lappeng/api/stacks/KeyCounter;)[Lappeng/api/stacks/KeyCounter;"))
    private KeyCounter[] appliedenhancements$guardInputs(IPatternDetails pattern,
            ICraftingInventory inventory, Level level, KeyCounter outputs,
            KeyCounter remainders, Operation<KeyCounter[]> original) {
        var guarded = AelisCycleExecutionApi.guardInputs(appliedenhancements$cycleRuntime, pattern.getDefinition(), inventory);
        return guarded == null ? null : original.call(pattern, guarded, level, outputs, remainders);
    }

    @WrapMethod(method = "executeCrafting")
    private int appliedenhancements$withCycleScope(int maxPatterns, CraftingService craftingService,
            IEnergyService energyService, Level level, Operation<Integer> original) {
        try (var scope = AelisCycleDispatchScope.open(() -> appliedenhancements$cycleRuntime)) {
            int executed;
            appliedenhancements$dispatchDepth++;
            try {
                executed = original.call(maxPatterns, craftingService, energyService, level);
            } finally {
                appliedenhancements$dispatchDepth--;
            }
            appliedenhancements$finishReturnedCycle();
            return executed;
        }
    }

    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean appliedenhancements$advanceCycle(ICraftingProvider provider,
            IPatternDetails pattern, KeyCounter[] inputs, Operation<Boolean> original) {
        var runtime = appliedenhancements$cycleRuntime;
        long crafts = AelisCycleDispatch.dispatchedProviderPush(
                runtime, pattern.getDefinition(), inputs);
        var previous = crafts > 0 ? runtime.snapshot() : null;
        if (previous != null) {
            runtime.patternDispatched(pattern.getDefinition(), crafts);
        }
        boolean accepted = false;
        try {
            accepted = original.call(provider, pattern, inputs);
            return accepted;
        } finally {
            if (!accepted && previous != null) {
                appliedenhancements$cycleRuntime = AelisCycleRuntimeController.withCyclePhase(runtime.plan(), previous);
            }
        }
    }

    @WrapMethod(method = "insert")
    private long appliedenhancements$retainOutput(AEKey key, long amount, Actionable mode,
            Operation<Long> original) {
        var runtime = appliedenhancements$cycleRuntime;
        long before = runtime == null || key == null ? 0 : getWaitingFor(key);
        long accepted = appliedenhancements$insertRetainedOutput(key, amount, mode, original);
        if (runtime != null && mode == Actionable.MODULATE && key != null) {
            runtime.recordReturned(key, Math.max(0, Math.min(amount, before - getWaitingFor(key))));
            appliedenhancements$finishReturnedCycle();
        }
        return accepted;
    }

    @Unique
    private long appliedenhancements$insertRetainedOutput(AEKey key, long amount, Actionable mode,
            Operation<Long> original) {
        var runtime = appliedenhancements$cycleRuntime;
        var output = getFinalJobOutput();
        if (runtime == null || !runtime.hasActiveSeedProtection() || amount <= 0
                || key == null || output == null || !key.matches(output)) {
            return original.call(key, amount, mode);
        }
        long stored = getInventory().extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        amount = Math.min(amount, getWaitingFor(key));
        if (amount <= 0) {
            return 0;
        }
        long retain = runtime.amountToRetain(key, amount, stored);
        if (retain <= 0) {
            return original.call(key, amount, mode);
        }
        long retained;
        boolean previous = appliedenhancements$retainingCycleOutput;
        appliedenhancements$retainingCycleOutput = true;
        try {
            // AdvancedAE handles waiting counts, elapsed work and inventory insertion itself.
            retained = original.call(key, retain, mode);
        } finally {
            appliedenhancements$retainingCycleOutput = previous;
        }
        if (retained < retain || amount == retain) {
            return retained;
        }
        return retained + original.call(key, amount - retain, mode);
    }

    @WrapOperation(method = "insert", at = @At(value = "INVOKE",
            target = "Lappeng/api/stacks/AEKey;matches(Lappeng/api/stacks/GenericStack;)Z"))
    private boolean appliedenhancements$storeProtectedOutput(AEKey key, GenericStack output,
            Operation<Boolean> original) {
        return !appliedenhancements$retainingCycleOutput && original.call(key, output);
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"))
    private void appliedenhancements$saveCycle(CompoundTag tag, HolderLookup.Provider registries,
            CallbackInfo callback) {
        if (hasJob() && appliedenhancements$cycleRuntime != null
                && appliedenhancements$cycleRuntime.hasActiveSeedProtection()) {
            tag.put(APPLIEDENHANCEMENTS_CYCLE_TAG,
                    AelisCycleExecutionApi.writeRuntime(appliedenhancements$cycleRuntime, registries));
        }
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void appliedenhancements$loadCycle(CompoundTag tag, HolderLookup.Provider registries,
            CallbackInfo callback) {
        boolean saved = hasJob() && tag.contains(APPLIEDENHANCEMENTS_CYCLE_TAG);
        appliedenhancements$cycleRuntime = saved
                ? AelisCycleExecutionApi.readRuntime(tag.getCompound(APPLIEDENHANCEMENTS_CYCLE_TAG), registries).orElse(null) : null;
        if (saved && appliedenhancements$cycleRuntime == null) {
            AppliedEnhancements.LOGGER.error("Invalid AELIS quantum CPU cycle state; cancelling job safely");
            cancel();
        } else if (saved) {
            AdvancedAeCycleRecovery.reconcile(this, appliedenhancements$cycleRuntime);
        } else if (!saved && hasJob()) {
            appliedenhancements$cycleRuntime = AdvancedAeCycleRecovery.recover(this, getInventory());
        }
        appliedenhancements$cycleRuntime = AdvancedAeCycleRecovery.prepare(
                this, appliedenhancements$cycleRuntime);
    }

    @WrapMethod(method = "finishJob")
    private void appliedenhancements$awaitCycleReturns(boolean success, Operation<Void> original) {
        if (success && appliedenhancements$cycleRuntime != null
                && (!appliedenhancements$cycleRuntime.isCyclePhaseComplete()
                        || appliedenhancements$dispatchDepth > 0)) return;
        original.call(success);
    }

    @Unique
    private void appliedenhancements$finishReturnedCycle() {
        if (appliedenhancements$dispatchDepth == 0 && hasJob() && appliedenhancements$cycleRuntime != null
                && appliedenhancements$cycleRuntime.isCyclePhaseComplete()
                && AdvancedAeCycleRecovery.isOutputComplete(this)) {
            finishJob(true);
        }
    }

    @Inject(method = "finishJob", at = @At("TAIL"))
    private void appliedenhancements$finishCycle(boolean success, CallbackInfo callback) {
        appliedenhancements$cycleRuntime = null;
    }
}
