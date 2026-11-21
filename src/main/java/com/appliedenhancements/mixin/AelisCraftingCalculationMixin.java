package com.appliedenhancements.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.api.AelisCraftingPlanner;
import com.appliedenhancements.api.AelisExactCraftingPlanApi;
import com.appliedenhancements.api.AelisExactRequest;
import com.appliedenhancements.runtime.CraftingSubmissionScope;
import com.appliedenhancements.runtime.ExactRequestScope;
import com.appliedenhancements.Config;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.math.BigInteger;
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

    @Shadow @Final private long requestedAmount;
    @Shadow @Final private CalculationStrategy strategy;

    @Shadow
    public abstract AEKey getOutput();

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
    @Unique
    private boolean appliedenhancements$probingExactPartial;
    @Unique
    private boolean appliedenhancements$exactProbeShortage;

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
        var aelisEnabled = molecularmanipulator$aelisEnabled || ExactRequestScope.current() != null;
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


    @WrapOperation(method = "computePlan", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lappeng/crafting/CraftingCalculation;runCraftAttempt(ZJ)Lappeng/crafting/CraftingPlan;"))
    private CraftingPlan appliedenhancements$searchExactPartial(CraftingCalculation calculation,
            boolean simulation, long amount, Operation<CraftingPlan> original) throws InterruptedException {
        var request = ExactRequestScope.current();
        if (strategy != CalculationStrategy.CRAFT_LESS || request == null || request.amount().bitLength() <= 63) {
            return original.call(calculation, simulation, amount);
        }

        appliedenhancements$probingExactPartial = true;
        try {
            var full = appliedenhancements$probeExactAmount(calculation, amount, request, original);
            if (full != null) {
                return full;
            }

            // Bracket the attainable quantity from below before bisecting. A huge
            // request with little stock should not cost one attempt per input bit.
            BigInteger lower = BigInteger.valueOf(Long.MAX_VALUE);
            BigInteger upper = request.amount();
            CraftingPlan best = null;
            BigInteger step = BigInteger.ONE;
            boolean expanding = true;
            while (lower.add(BigInteger.ONE).compareTo(upper) < 0) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                BigInteger trial = expanding ? lower.add(step) : lower.add(upper).shiftRight(1);
                if (trial.compareTo(upper) >= 0) {
                    expanding = false;
                    continue;
                }
                var plan = appliedenhancements$probeExactAmount(
                        calculation, amount, new AelisExactRequest(trial), original);
                if (plan != null) {
                    lower = trial;
                    best = plan;
                    if (expanding) {
                        step = step.shiftLeft(1);
                    }
                } else if (appliedenhancements$exactProbeShortage) {
                    upper = trial;
                    expanding = false;
                } else {
                    // A fallback or an inexact result cannot shrink the search range.
                    break;
                }
            }
            if (best != null) {
                return best;
            }
        } finally {
            appliedenhancements$probingExactPartial = false;
            appliedenhancements$exactProbeShortage = false;
        }

        // The host searches strictly below its original amount. Try the boundary
        // itself before handing back to its long search and full missing report.
        try (var scope = new ExactRequestScope(new AelisExactRequest(BigInteger.valueOf(amount)))) {
            return original.call(calculation, simulation, amount);
        }
    }

    @Unique
    private CraftingPlan appliedenhancements$probeExactAmount(CraftingCalculation calculation,
            long projection, AelisExactRequest request, Operation<CraftingPlan> original)
            throws InterruptedException {
        appliedenhancements$exactProbeShortage = false;
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        try (var scope = new ExactRequestScope(request)) {
            var plan = original.call(calculation, false, projection);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (plan != null && (plan.simulation()
                    || !getOutput().equals(plan.finalOutput().what())
                    || !request.amount().equals(AelisExactCraftingPlanApi.getFinalOutputAmount(plan)))) {
                appliedenhancements$exactProbeShortage = false;
                return null;
            }
            return plan;
        }
    }

    @WrapMethod(method = "runCraftAttempt")
    private CraftingPlan appliedenhancements$exactAttempt(boolean simulation, long amount,
            Operation<CraftingPlan> original) throws InterruptedException {
        var request = ExactRequestScope.current();
        if (request == null) return original.call(simulation, amount);
        try (var scope = new ExactRequestScope(request.attempt(amount, requestedAmount))) {
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
        var aelisEnabled = molecularmanipulator$aelisEnabled || ExactRequestScope.current() != null;

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
        if (!result.applied() && Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (result.branchFailure() != null) {
            if (appliedenhancements$probingExactPartial) {
                appliedenhancements$exactProbeShortage = true;
            }
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

        if (appliedenhancements$probingExactPartial) {
            // Let AE2 end the attempt without treating its long projection as exact.
            throw new CraftBranchFailure(getOutput(), requestedAmount);
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
        var plan = callback.getReturnValue();
        if ((Object) plan instanceof AelisCalculationPathCarrier carrier) {
            // An external API caller may already have attached the actual path.
            if (carrier.molecularmanipulator$getCalculationPath() != AelisCalculationPath.AELIS) {
                carrier.molecularmanipulator$setCalculationPath(AelisCalculationPath.AELIS);
            }
        }
        var exact = ExactRequestScope.current();
        if (exact != null && plan != null && plan.finalOutput().amount() == exact.projection()
                && (Object) plan instanceof AelisBigIntegerCraftAmountsCarrier carrier) {
            carrier.appliedenhancements$setBigIntegerFinalAmount(exact.amount());
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
