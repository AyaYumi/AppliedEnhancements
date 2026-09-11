package com.appliedenhancements.mixin;

import java.util.ArrayDeque;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.appliedenhancements.runtime.MolecularBalancedBatchScope;
import com.appliedenhancements.api.AelisCycleExecutionApi;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import com.appliedenhancements.runtime.AelisCycleDispatch;
import com.appliedenhancements.runtime.AelisCycleDispatchScope;
import com.appliedenhancements.runtime.AelisCycleRuntimePreparation;
import com.appliedenhancements.runtime.DataEnergisticsOrderCompletion;
import com.appliedenhancements.AppliedEnhancements;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * Brackets all pushes to an opted-in provider during one CPU scheduling pass.
 */
// Keep the guarded inventory outside batching wrappers, including their extractions after original.call.
@Mixin(value = CraftingCpuLogic.class, remap = false, priority = 1100)
public abstract class CraftingCpuLogicBatchMixin {
    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    private ListCraftingInventory inventory;

    @Shadow
    @Final
    private CraftingCPUCluster cluster;

    @Shadow
    public abstract void cancel();

    @Shadow
    private void finishJob(boolean success) { throw new AssertionError(); }

    @Unique
    private static final ThreadLocal<ArrayDeque<MolecularBalancedBatchScope>>
            APPLIEDENHANCEMENTS_BATCH_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    @Unique
    private static final String APPLIEDENHANCEMENTS_CYCLE_RUNTIME_TAG =
            "appliedenhancementsCycleRuntime";

    @Unique
    private AelisCycleRuntimeController appliedenhancements$cycleRuntime;

    @Unique private int appliedenhancements$dispatchDepth;

    @ModifyVariable(method = "trySubmitJob", at = @At("HEAD"), argsOnly = true)
    private ICraftingPlan appliedenhancements$prepareCyclePlan(ICraftingPlan plan) {
        return AelisCycleExecutionApi.preparePlan(plan);
    }

    @Inject(method = "trySubmitJob", at = @At("RETURN"))
    private void appliedenhancements$startCycleRuntime(
            IGrid grid,
            ICraftingPlan plan,
            IActionSource source,
            ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (callback.getReturnValue() != null
                && callback.getReturnValue().successful()) {
            appliedenhancements$cycleRuntime = AelisCycleExecutionApi.getPlan(plan)
                    .map(AelisCycleRuntimeController::withCyclePhase)
                    .orElse(null);
        }
    }

    @WrapMethod(method = "executeCrafting")
    private int appliedenhancements$withBalancedBatchScope(int maxPatterns,
            CraftingService craftingService, IEnergyService energyService, Level level,
            Operation<Integer> original) {
        var scopes = APPLIEDENHANCEMENTS_BATCH_SCOPES.get();
        try (var cycleScope = AelisCycleDispatchScope.open(() -> appliedenhancements$cycleRuntime);
                var scope = new MolecularBalancedBatchScope()) {
            scopes.push(scope);
            try {
                int executed;
                appliedenhancements$dispatchDepth++;
                try {
                    executed = original.call(maxPatterns, craftingService, energyService, level);
                } finally {
                    appliedenhancements$dispatchDepth--;
                }
                appliedenhancements$finishReturnedCycle();
                return executed;
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
            IPatternDetails details, KeyCounter[] inputHolder, Operation<Boolean> original,
            @Local(ordinal = 0) KeyCounter expectedOutputs) {
        var scopes = APPLIEDENHANCEMENTS_BATCH_SCOPES.get();
        if (!scopes.isEmpty()) {
            scopes.peek().beginIfNeeded(provider, inputHolder);
        }
        var completionJob = job;
        var virtualCompletion = completionJob == null ? null : DataEnergisticsOrderCompletion.beforeNativePush(
                this, ((ExecutingCraftingJobCycleAccessor) completionJob).appliedenhancements$getFinalOutput(),
                expectedOutputs);
        var runtime = appliedenhancements$cycleRuntime;
        AelisCycleRuntimeController.State previousState = null;
        if (runtime != null && !runtime.isComplete()
                && runtime.currentStep()
                        .map(step -> step.patternDefinition()
                                .equals(details.getDefinition()))
                        .orElse(false)) {
            previousState = runtime.snapshot();
            runtime.patternDispatched(details.getDefinition(),
                    AelisCycleDispatch.dispatchedProviderPush(
                            runtime, details.getDefinition(), inputHolder));
        }
        boolean pushed;
        try {
            pushed = original.call(provider, details, inputHolder);
        } catch (RuntimeException | Error failure) {
            if (previousState != null) {
                appliedenhancements$cycleRuntime = AelisCycleRuntimeController.withCyclePhase(
                        runtime.plan(), previousState);
            }
            throw failure;
        }
        if (!pushed && previousState != null) {
            appliedenhancements$cycleRuntime = AelisCycleRuntimeController.withCyclePhase(
                    runtime.plan(), previousState);
        }
        if (pushed && job == completionJob && virtualCompletion != null) {
            virtualCompletion.reconcileAcceptedPush();
        }
        return pushed;
    }

    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs(Lappeng/api/crafting/IPatternDetails;Lappeng/crafting/inv/ICraftingInventory;Lnet/minecraft/world/level/Level;Lappeng/api/stacks/KeyCounter;Lappeng/api/stacks/KeyCounter;)[Lappeng/api/stacks/KeyCounter;"))
    private KeyCounter[] appliedenhancements$guardCyclePatternInputs(
            IPatternDetails details,
            ICraftingInventory sourceInventory,
            Level level,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            Operation<KeyCounter[]> original) {
        var guarded = AelisCycleExecutionApi.guardInputs(
                appliedenhancements$cycleRuntime, details.getDefinition(), sourceInventory);
        return guarded == null ? null : original.call(
                details, guarded, level, expectedOutputs, expectedContainerItems);
    }

    @WrapMethod(method = "insert")
    private long appliedenhancements$retainCycleFinalOutput(
            AEKey what,
            long amount,
            Actionable mode,
            Operation<Long> original) {
        var runtime = appliedenhancements$cycleRuntime;
        var waiting = runtime == null || job == null ? null
                : ((ExecutingCraftingJobCycleAccessor) job).appliedenhancements$getWaitingFor();
        long before = waiting == null || what == null ? 0 : waiting.list.get(what);
        long accepted = appliedenhancements$insertRetainedOutput(what, amount, mode, original);
        if (runtime != null && mode == Actionable.MODULATE && what != null) {
            // Standalone links return zero for final output routed back to ME, although
            // the CPU has settled its waiting count. Track that settlement as well.
            long after = waiting == null ? 0 : waiting.list.get(what);
            runtime.recordReturned(what, Math.max(0, Math.min(amount, before - after)));
            appliedenhancements$finishReturnedCycle();
        }
        return accepted;
    }

    @Unique
    private long appliedenhancements$insertRetainedOutput(
            AEKey what, long amount, Actionable mode, Operation<Long> original) {
        var runtime = appliedenhancements$cycleRuntime;
        var currentJob = job;
        if (runtime == null || !runtime.hasActiveSeedProtection()
                || currentJob == null
                || what == null
                || amount <= 0) {
            return original.call(what, amount, mode);
        }
        var jobAccess = (ExecutingCraftingJobCycleAccessor) currentJob;
        var finalOutput = jobAccess.appliedenhancements$getFinalOutput();
        if (finalOutput == null || !what.matches(finalOutput)) {
            return original.call(what, amount, mode);
        }

        long stored = inventory.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
        long retainable = runtime.amountToRetain(what, amount, stored);
        if (retainable <= 0) {
            return original.call(what, amount, mode);
        }
        var waitingFor = jobAccess.appliedenhancements$getWaitingFor();
        long accepted = waitingFor.extract(what, amount, Actionable.SIMULATE);
        long retained = Math.min(retainable, accepted);
        if (retained <= 0) {
            return original.call(what, amount, mode);
        }

        long forwardedAmount = amount - retained;
        if (mode == Actionable.SIMULATE) {
            long forwarded = forwardedAmount <= 0
                    ? 0
                    : original.call(what, forwardedAmount, mode);
            return Math.min(accepted, retained + forwarded);
        }

        long removed = waitingFor.extract(what, retained, Actionable.MODULATE);
        if (removed != retained) {
            throw new IllegalStateException(
                    "Cycle runtime waiting-for inventory changed during retention");
        }
        ((ElapsedTimeTrackerAccessor) jobAccess.appliedenhancements$getTimeTracker())
                .appliedenhancements$decrementItems(retained, what.getType());
        inventory.insert(what, retained, Actionable.MODULATE);
        cluster.markDirty();
        long forwarded = forwardedAmount <= 0
                ? 0
                : original.call(what, forwardedAmount, mode);
        return retained + forwarded;
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"))
    private void appliedenhancements$writeCycleRuntime(
            CompoundTag data,
            HolderLookup.Provider registries,
            CallbackInfo callback) {
        if (appliedenhancements$cycleRuntime != null
                && appliedenhancements$cycleRuntime.hasActiveSeedProtection()) {
            data.put(
                    APPLIEDENHANCEMENTS_CYCLE_RUNTIME_TAG,
                    AelisCycleExecutionApi.writeRuntime(
                            appliedenhancements$cycleRuntime, registries));
        }
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void appliedenhancements$readCycleRuntime(
            CompoundTag data,
            HolderLookup.Provider registries,
            CallbackInfo callback) {
        boolean hasRuntime = job != null
                && data.contains(APPLIEDENHANCEMENTS_CYCLE_RUNTIME_TAG);
        appliedenhancements$cycleRuntime = hasRuntime
                ? AelisCycleExecutionApi.readRuntime(
                        data.getCompound(APPLIEDENHANCEMENTS_CYCLE_RUNTIME_TAG),
                        registries).orElse(null)
                : null;
        if (hasRuntime && appliedenhancements$cycleRuntime == null) {
            AppliedEnhancements.LOGGER.error(
                    "Cancelling AE2 crafting job because its AELIS cycle runtime state is invalid");
            cancel();
        } else if (hasRuntime) {
            appliedenhancements$cycleRuntime = AelisCycleRuntimePreparation.prepareNative(
                    appliedenhancements$cycleRuntime, job, cluster.getLevel());
        }
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
        if (appliedenhancements$dispatchDepth == 0 && job != null && appliedenhancements$cycleRuntime != null
                && appliedenhancements$cycleRuntime.isCyclePhaseComplete()
                && ((ExecutingCraftingJobCycleAccessor) job).appliedenhancements$getRemainingAmount() == 0) {
            finishJob(true);
        }
    }

    @Inject(method = "finishJob", at = @At("TAIL"))
    private void appliedenhancements$clearCycleRuntime(
            boolean success, CallbackInfo callback) {
        appliedenhancements$cycleRuntime = null;
    }

}
