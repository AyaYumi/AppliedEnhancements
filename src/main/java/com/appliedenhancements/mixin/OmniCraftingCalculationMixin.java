package com.appliedenhancements.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.api.MaxFastCraftingPlanner;
import com.appliedenhancements.Config;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressCarrier;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressRequester;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPath;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.OmniCraftingTreeNodeBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;

@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class OmniCraftingCalculationMixin
        implements CraftingCalculationProgressCarrier {
    @Unique
    private static final int MOLECULARMANIPULATOR_MAX_BACKGROUND_CALCULATIONS =
            Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    @Unique
    private static final Semaphore MOLECULARMANIPULATOR_CALCULATION_SLOTS =
            new Semaphore(MOLECULARMANIPULATOR_MAX_BACKGROUND_CALCULATIONS, true);
    @Unique
    private static final Semaphore MOLECULARMANIPULATOR_INTERACTIVE_SLOT = new Semaphore(1, true);

    @Shadow
    abstract void handlePausing() throws InterruptedException;

    @Shadow
    public abstract KeyCounter getMissingItems();

    @Shadow
    public abstract boolean isSimulation();

    @Unique
    private boolean molecularmanipulator$maxFastEnabled;
    @Unique
    private boolean molecularmanipulator$interactiveRequest;
    @Unique
    private MaxFastCraftingPlanner molecularmanipulator$maxFastSession;
    @Unique
    private long molecularmanipulator$maxFastNodeCount = -1;
    @Unique
    private OmniCalculationPath molecularmanipulator$calculationPath =
            OmniCalculationPath.AE2_NATIVE;
    @Unique
    private CraftingCalculationProgressHandle molecularmanipulator$calculationProgress;

    @Override
    public CraftingCalculationProgressHandle
            molecularmanipulator$getCalculationProgressHandle() {
        return molecularmanipulator$calculationProgress;
    }

    @Override
    public void molecularmanipulator$setCalculationProgressHandle(
            CraftingCalculationProgressHandle progress) {
        molecularmanipulator$calculationProgress = progress;
    }

    @Inject(method = "<init>", at = @At(value = "FIELD",
            target = "Lappeng/crafting/CraftingCalculation;networkInv:Lappeng/crafting/inv/NetworkCraftingSimulationState;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void molecularmanipulator$bindCalculationProgress(Level level, IGrid grid,
            ICraftingSimulationRequester requester, GenericStack output,
            CalculationStrategy strategy, CallbackInfo callback) {
        if (requester instanceof CraftingCalculationProgressRequester tracked) {
            molecularmanipulator$calculationProgress = tracked.progress();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void molecularmanipulator$checkMaxFastEnabled(Level level, IGrid grid,
            ICraftingSimulationRequester requester, GenericStack output,
            CalculationStrategy strategy, CallbackInfo callback) {
        molecularmanipulator$maxFastEnabled = molecularmanipulator$shouldEnableMaxFast();
        var source = requester.getActionSource();
        molecularmanipulator$interactiveRequest = source != null && source.player().isPresent();
    }

    @Unique
    private static boolean molecularmanipulator$shouldEnableMaxFast() {
        return Config.ENABLE_AUTOMATIC_MAX_FAST_PLANNER.get();
    }

    @WrapMethod(method = "run")
    private ICraftingPlan molecularmanipulator$trackOmniCalculation(
            Operation<ICraftingPlan> original) {
        var maxFastEnabled = molecularmanipulator$maxFastEnabled;
        var progress = molecularmanipulator$calculationProgress;
        if (!maxFastEnabled) {
            if (progress != null) {
                progress.beginAe2(OmniCalculationPath.AE2_NATIVE);
            }
            try {
                ICraftingPlan plan = original.call();
                if (progress != null) {
                    progress.complete(molecularmanipulator$finalCalculationPath(plan));
                }
                return plan;
            } catch (RuntimeException | Error failure) {
                molecularmanipulator$finishFailedProgress(progress, failure);
                throw failure;
            }
        }

        boolean backgroundSlot = false;
        boolean interactiveSlot = false;
        if (Thread.currentThread().isInterrupted()) {
            if (progress != null) {
                progress.cancel();
            }
            throw new CancellationException("Crafting calculation was cancelled before execution");
        }
        if (progress != null) {
            progress.waitingForSlot();
        }
        try {
            if (molecularmanipulator$interactiveRequest) {
                backgroundSlot = MOLECULARMANIPULATOR_CALCULATION_SLOTS.tryAcquire();
                if (!backgroundSlot) {
                    molecularmanipulator$acquireCalculationSlot(
                            MOLECULARMANIPULATOR_INTERACTIVE_SLOT);
                    interactiveSlot = true;
                }
            } else {
                molecularmanipulator$acquireCalculationSlot(
                        MOLECULARMANIPULATOR_CALCULATION_SLOTS);
                backgroundSlot = true;
            }
        } catch (CancellationException cancellation) {
            if (progress != null) {
                progress.cancel();
            }
            throw cancellation;
        }
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(
                        "Crafting calculation was cancelled before execution");
            }
            ICraftingPlan plan = original.call();
            if (progress != null) {
                progress.complete(molecularmanipulator$finalCalculationPath(plan));
            }
            return plan;
        } catch (RuntimeException | Error failure) {
            molecularmanipulator$finishFailedProgress(progress, failure);
            throw failure;
        } finally {
            if (backgroundSlot) {
                MOLECULARMANIPULATOR_CALCULATION_SLOTS.release();
            }
            if (interactiveSlot) {
                MOLECULARMANIPULATOR_INTERACTIVE_SLOT.release();
            }
        }
    }

    @Unique
    private static void molecularmanipulator$finishFailedProgress(
            CraftingCalculationProgressHandle progress, Throwable failure) {
        if (progress == null) {
            return;
        }
        if (Thread.currentThread().isInterrupted()
                || molecularmanipulator$isCancellation(failure)) {
            progress.cancel();
        } else {
            progress.fail();
        }
    }

    @Unique
    private static boolean molecularmanipulator$isCancellation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CancellationException
                    || current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private OmniCalculationPath molecularmanipulator$finalCalculationPath(
            ICraftingPlan plan) {
        OmniCalculationPath carriedPath = plan instanceof OmniCalculationPathCarrier carrier
                ? carrier.molecularmanipulator$getCalculationPath()
                : null;
        if (carriedPath != null && carriedPath != OmniCalculationPath.AE2_NATIVE) {
            return carriedPath;
        }

        if (!(plan instanceof CraftingPlan)) {
            return OmniCalculationPath.EXTERNAL;
        }

        return carriedPath != null ? carriedPath : molecularmanipulator$calculationPath;
    }

    @Unique
    private static void molecularmanipulator$acquireCalculationSlot(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException(
                    "Crafting calculation was cancelled while waiting for an execution slot");
        }
    }


    @WrapOperation(method = "runCraftAttempt", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/CraftingTreeNode;request(Lappeng/crafting/inv/CraftingSimulationState;JLappeng/api/stacks/KeyCounter;)V"))
    private void molecularmanipulator$aggregateSafeRecipeTree(CraftingTreeNode tree,
            CraftingSimulationState inventory, long requestedAmount, KeyCounter containerItems,
            Operation<Void> original) throws CraftBranchFailure, InterruptedException {
        molecularmanipulator$maxFastNodeCount = -1;
        molecularmanipulator$calculationPath = OmniCalculationPath.AE2_NATIVE;
        var progress = molecularmanipulator$calculationProgress;
        var maxFastEnabled = molecularmanipulator$maxFastEnabled;

        if (!maxFastEnabled || containerItems != null) {
            if (progress != null) {
                progress.beginAe2(OmniCalculationPath.AE2_NATIVE);
            }
            original.call(tree, inventory, requestedAmount, containerItems);
            return;
        }

        var session = molecularmanipulator$maxFastSession;
        if (session == null) {
            session = MaxFastCraftingPlanner.createConfigured(
                    this::handlePausing,
                    molecularmanipulator$createProgressListener(progress));
            molecularmanipulator$maxFastSession = session;
        }

        if (progress != null) {
            progress.beginMaxFastCompilation();
        }

        KeyCounter missingItems = getMissingItems();
        AEKey requestedKey = ((OmniCraftingTreeNodeBridge) tree)
                .molecularmanipulator$getWhat();
        var result = session.tryExecute(
                tree, inventory, requestedAmount, isSimulation(), missingItems);
        if (result.branchFailure() != null) {
            throw result.branchFailure();
        }
        if (result.applied()) {
            molecularmanipulator$calculationPath = OmniCalculationPath.MAX_FAST;
            if (!result.nativeNodeCount()) {
                molecularmanipulator$maxFastNodeCount = Math.min(
                        result.logicalNodeCount(), Long.MAX_VALUE / 8);
            }
            if (Config.MAX_FAST_DIAGNOSTICS.get()) {
                com.appliedenhancements.AppliedEnhancements.LOGGER.info(
                        "Omni MAX_FAST applied: key={}, amount={}, simulation={}, uniqueNodes={}, mergedOccurrences={}, barriers={}, logicalNodes={}, compileMs={}, executeMs={}",
                        requestedKey, requestedAmount, isSimulation(),
                        result.uniqueNodes(), result.mergedOccurrences(),
                        result.barrierCount(), result.logicalNodeCount(),
                        result.compileNanos() / 1_000_000.0,
                        result.executionNanos() / 1_000_000.0);
            }
            return;
        }

        if (result.error() != null) {
            com.appliedenhancements.AppliedEnhancements.LOGGER.warn(
                    "Omni MAX_FAST encountered an internal compatibility error and fell back to AE2",
                    result.error());
        } else if (Config.MAX_FAST_DIAGNOSTICS.get()) {
            com.appliedenhancements.AppliedEnhancements.LOGGER.info(
                    "Omni MAX_FAST fallback: key={}, amount={}, simulation={}, reason={}, uniqueNodes={}, mergedOccurrences={}, barriers={}, compileMs={}, executeMs={}",
                    requestedKey, requestedAmount, isSimulation(),
                    result.fallbackReason(), result.uniqueNodes(),
                    result.mergedOccurrences(), result.barrierCount(),
                    result.compileNanos() / 1_000_000.0,
                    result.executionNanos() / 1_000_000.0);
        }
        molecularmanipulator$calculationPath = OmniCalculationPath.AE2_FALLBACK;
        if (progress != null) {
            progress.beginAe2(OmniCalculationPath.AE2_FALLBACK);
        }
        original.call(tree, inventory, requestedAmount, containerItems);
    }

    @Unique
    private static MaxFastCraftingPlanner.ProgressListener
            molecularmanipulator$createProgressListener(
            CraftingCalculationProgressHandle progress) {
        if (progress == null) {
            return MaxFastCraftingPlanner.ProgressListener.NONE;
        }
        return new MaxFastCraftingPlanner.ProgressListener() {
            @Override
            public void compilationStarted() {
                progress.beginMaxFastCompilation();
            }

            @Override
            public void nodeDiscovered() {
                progress.nodeDiscovered();
            }

            @Override
            public void compilationStep() {
                progress.workStep();
            }

            @Override
            public void executionStarted(long totalUnits) {
                progress.beginMaxFastExecution(totalUnits);
            }

            @Override
            public void executionStep() {
                progress.executionStep();
            }
        };
    }

    @Inject(method = "runCraftAttempt", at = @At("RETURN"))
    private void molecularmanipulator$attachCalculationPath(
            boolean simulation, long amount,
            CallbackInfoReturnable<CraftingPlan> callback) {
        if ((Object) callback.getReturnValue() instanceof OmniCalculationPathCarrier carrier) {
            carrier.molecularmanipulator$setCalculationPath(molecularmanipulator$calculationPath);
        }
    }

    @Inject(method = "runCraftAttempt", at = @At("HEAD"))
    private void molecularmanipulator$beginCraftAttempt(
            boolean simulation, long amount,
            CallbackInfoReturnable<CraftingPlan> callback) {
        var progress = molecularmanipulator$calculationProgress;
        if (progress != null) {
            progress.beginAttempt(simulation);
        }
    }

    @WrapOperation(method = "runCraftAttempt", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/inv/CraftingSimulationState;buildCraftingPlan(Lappeng/crafting/inv/CraftingSimulationState;Lappeng/crafting/CraftingCalculation;J)Lappeng/crafting/CraftingPlan;"))
    private CraftingPlan molecularmanipulator$trackPlanBuilding(
            CraftingSimulationState inventory,
            CraftingCalculation calculation,
            long amount,
            Operation<CraftingPlan> original) {
        var progress = molecularmanipulator$calculationProgress;
        if (progress != null) {
            progress.beginBuildingPlan();
        }
        return original.call(inventory, calculation, amount);
    }

    @WrapOperation(method = "runCraftAttempt", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/CraftingTreeNode;getNodeCount()J"))
    private long molecularmanipulator$useAggregatedNodeCount(CraftingTreeNode tree,
            Operation<Long> original) {
        return molecularmanipulator$maxFastNodeCount >= 0
                ? molecularmanipulator$maxFastNodeCount
                : original.call(tree);
    }
}
