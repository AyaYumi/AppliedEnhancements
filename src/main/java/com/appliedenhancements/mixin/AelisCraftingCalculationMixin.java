package com.appliedenhancements.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.api.AelisCraftingPlanner;
import com.appliedenhancements.api.AelisExactRequest;
import com.appliedenhancements.runtime.CraftingSubmissionScope;
import com.appliedenhancements.runtime.ExactRequestScope;
import com.appliedenhancements.Config;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
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
public abstract class AelisCraftingCalculationMixin {
    @Unique
    private static final int MOLECULARMANIPULATOR_MAX_BACKGROUND_CALCULATIONS =
            Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    @Unique
    private static final Semaphore MOLECULARMANIPULATOR_CALCULATION_SLOTS =
            new Semaphore(MOLECULARMANIPULATOR_MAX_BACKGROUND_CALCULATIONS, true);
    @Unique
    private static final Semaphore MOLECULARMANIPULATOR_INTERACTIVE_SLOT = new Semaphore(1, true);

    @Shadow private long requestedAmount;

    @Shadow
    abstract void handlePausing() throws InterruptedException;

    @Shadow
    public abstract KeyCounter getMissingItems();

    @Shadow
    public abstract boolean isSimulation();

    @Unique
    private boolean molecularmanipulator$aelisEnabled;
    @Unique
    private boolean molecularmanipulator$interactiveRequest;
    @Unique
    private AelisCraftingPlanner molecularmanipulator$aelisSession;
    @Unique
    private long molecularmanipulator$aelisNodeCount = -1;
    @Unique
    private AelisCalculationPath molecularmanipulator$calculationPath =
            AelisCalculationPath.AE2_NATIVE;
    @Unique
    private CraftingCalculationProgressHandle molecularmanipulator$calculationProgress;
    @Unique
    private ICraftingService molecularmanipulator$craftingService;
    @Unique
    private AelisExactRequest appliedenhancements$submittedExactAmount;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void molecularmanipulator$checkAelisEnabled(Level level, IGrid grid,
            ICraftingSimulationRequester requester, GenericStack output,
            CalculationStrategy strategy, CallbackInfo callback) {
        molecularmanipulator$calculationProgress = CraftingSubmissionScope.progress();
        molecularmanipulator$aelisEnabled = molecularmanipulator$shouldEnableAelis();
        var submitted = CraftingSubmissionScope.request();
        if (submitted != null && submitted.output().equals(output.what())
                && submitted.amount().bitLength() > 63
                && output.amount() == Long.MAX_VALUE) {
            appliedenhancements$submittedExactAmount = new AelisExactRequest(submitted.amount());
        }
        molecularmanipulator$craftingService = grid.getCraftingService();
        var source = requester.getActionSource();
        molecularmanipulator$interactiveRequest = source != null && source.player().isPresent();
    }

    @Unique
    private static boolean molecularmanipulator$shouldEnableAelis() {
        return Config.ENABLE_AUTOMATIC_AELIS_PLANNER.get();
    }

    @WrapMethod(method = "run")
    private ICraftingPlan molecularmanipulator$trackAelisCalculation(
            Operation<ICraftingPlan> original) {
        var aelisEnabled = molecularmanipulator$aelisEnabled || com.appliedenhancements.runtime.ExactRequestScope.current() != null;
        var progress = molecularmanipulator$calculationProgress;
        if (!aelisEnabled) {
            return original.call();
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
        try (var submittedAmount = appliedenhancements$submittedExactAmount != null
                && ExactRequestScope.current() == null
                        ? new ExactRequestScope(appliedenhancements$submittedExactAmount) : null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(
                        "Crafting calculation was cancelled before execution");
            }
            ICraftingPlan plan = original.call();
            if (appliedenhancements$submittedExactAmount != null
                    && molecularmanipulator$finalCalculationPath(plan) == AelisCalculationPath.AELIS
                    && plan.finalOutput().amount() == appliedenhancements$submittedExactAmount.projection()) {
                ((com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier) plan)
                        .appliedenhancements$setBigIntegerFinalAmount(appliedenhancements$submittedExactAmount.amount());
            }
            if (progress != null) {
                if (molecularmanipulator$finalCalculationPath(plan) == AelisCalculationPath.AELIS) {
                    progress.complete(AelisCalculationPath.AELIS);
                } else {
                    progress.stopTracking();
                }
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
    private AelisCalculationPath molecularmanipulator$finalCalculationPath(
            ICraftingPlan plan) {
        AelisCalculationPath carriedPath = plan instanceof AelisCalculationPathCarrier carrier
                ? carrier.molecularmanipulator$getCalculationPath()
                : null;
        if (carriedPath == AelisCalculationPath.AELIS) {
            return AelisCalculationPath.AELIS;
        }
        return AelisCalculationPath.AE2_NATIVE;
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


    @WrapMethod(method = "runCraftAttempt")
    private CraftingPlan appliedenhancements$exactAttempt(boolean simulation, long amount,
            Operation<CraftingPlan> original) throws InterruptedException {
        var request = com.appliedenhancements.runtime.ExactRequestScope.current();
        if (request == null) return original.call(simulation, amount);
        try (var scope = new com.appliedenhancements.runtime.ExactRequestScope(
                request.attempt(amount, requestedAmount))) {
            return original.call(simulation, amount);
        }
    }

    @WrapOperation(method = "runCraftAttempt", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/CraftingTreeNode;request(Lappeng/crafting/inv/CraftingSimulationState;JLappeng/api/stacks/KeyCounter;)V"))
    private void molecularmanipulator$aggregateSafeRecipeTree(CraftingTreeNode tree,
            CraftingSimulationState inventory, long requestedAmount, KeyCounter containerItems,
            Operation<Void> original) throws CraftBranchFailure, InterruptedException {
        molecularmanipulator$aelisNodeCount = -1;
        molecularmanipulator$calculationPath = AelisCalculationPath.AE2_NATIVE;
        var progress = molecularmanipulator$calculationProgress;
        var aelisEnabled = molecularmanipulator$aelisEnabled || com.appliedenhancements.runtime.ExactRequestScope.current() != null;

        if (!aelisEnabled || containerItems != null) {
            original.call(tree, inventory, requestedAmount, containerItems);
            return;
        }

        var session = molecularmanipulator$aelisSession;
        if (session == null) {
            session = AelisCraftingPlanner.createConfigured(
                    this::handlePausing,
                    molecularmanipulator$createProgressListener(progress),
                    molecularmanipulator$craftingService);
            molecularmanipulator$aelisSession = session;
        }

        if (progress != null) {
            progress.beginAelisCompilation();
        }

        KeyCounter missingItems = getMissingItems();
        var result = session.tryExecute(
                tree, inventory, requestedAmount, isSimulation(), missingItems);
        if (result.branchFailure() != null) {
            throw result.branchFailure();
        }
        if (result.applied()) {
            molecularmanipulator$calculationPath = AelisCalculationPath.AELIS;
            if (!result.nativeNodeCount()) {
                molecularmanipulator$aelisNodeCount = Math.min(
                        result.logicalNodeCount(), Long.MAX_VALUE / 8);
            }
            return;
        }

        if (progress != null) {
            progress.stopTracking();
        }
        original.call(tree, inventory, requestedAmount, containerItems);
    }

    @Unique
    private static AelisCraftingPlanner.ProgressListener
            molecularmanipulator$createProgressListener(
            CraftingCalculationProgressHandle progress) {
        if (progress == null) {
            return AelisCraftingPlanner.ProgressListener.NONE;
        }
        return new AelisCraftingPlanner.ProgressListener() {
            @Override
            public void compilationStarted() {
                progress.beginAelisCompilation();
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
                progress.beginAelisExecution(totalUnits);
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
        if (!CraftingPlannerIntervention.enabled()
                || molecularmanipulator$calculationPath != AelisCalculationPath.AELIS) return;
        if ((Object) callback.getReturnValue() instanceof AelisCalculationPathCarrier carrier) {
            // An external API caller may already have attached the actual path.
            if (carrier.molecularmanipulator$getCalculationPath() != AelisCalculationPath.AELIS) {
                carrier.molecularmanipulator$setCalculationPath(AelisCalculationPath.AELIS);
            }
        }
    }

    @Inject(method = "runCraftAttempt", at = @At("HEAD"))
    private void molecularmanipulator$beginCraftAttempt(
            boolean simulation, long amount,
            CallbackInfoReturnable<CraftingPlan> callback) {
        if (!CraftingPlannerIntervention.enabled()) return;
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
        if (!CraftingPlannerIntervention.enabled()
                || molecularmanipulator$calculationPath != AelisCalculationPath.AELIS) {
            return original.call(inventory, calculation, amount);
        }
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
        if (!CraftingPlannerIntervention.enabled()) {
            return original.call(tree);
        }
        return molecularmanipulator$aelisNodeCount >= 0
                ? molecularmanipulator$aelisNodeCount
                : original.call(tree);
    }
}
