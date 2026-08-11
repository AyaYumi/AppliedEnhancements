package com.appliedenhancements.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.helpers.PlayerSource;
import appeng.menu.MenuOpener;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.ae2.LongCraftingAmountMenuBridge;
import com.appliedenhancements.ae2.LongCraftingConfirmMenuBridge;
import com.appliedenhancements.Config;
import com.appliedenhancements.runtime.CraftingProgressSnapshotOrder;
import com.appliedenhancements.runtime.CraftingProgressTaskBinding;
import com.appliedenhancements.runtime.NativeCraftingLongSafety;
import com.appliedenhancements.runtime.TerminalAwareFuture;
import com.github.appliedenhancements.config.AppliedEnhancementsConfig;
import com.github.appliedenhancements.crafting.maxfast.OmniOrderedChoicePlanningRejectedException;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressRequester;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPath;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.OmniCalculationPathMenuBridge;
import com.github.appliedenhancements.network.CraftingCalculationPathPayload;
import com.github.appliedenhancements.network.CraftingCalculationProgressPayload;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuMixin implements CraftingCalculationProgressMenuBridge,
        LongCraftingConfirmMenuBridge, OmniCalculationPathMenuBridge {
    @Unique
    private static final AtomicLong appliedenhancements$nextProgressGeneration = new AtomicLong();

    @Shadow
    private ICraftingPlan result;

    @Shadow
    private AEKey whatToCraft;

    @Shadow
    private int amount;

    @Shadow
    private Future<ICraftingPlan> job;

    @Unique
    private final CraftingProgressTaskBinding appliedenhancements$progressBinding =
            new CraftingProgressTaskBinding();

    @Unique
    private CraftingCalculationProgressSnapshot appliedenhancements$calculationProgress =
            CraftingCalculationProgressSnapshot.idle();

    @Unique
    private long appliedenhancements$progressRevision;

    @Unique
    private long appliedenhancements$lastProgressSyncTick = Long.MIN_VALUE;

    @Unique
    private long appliedenhancements$terminalProgressSentGeneration = -1;

    @Unique
    private OmniCalculationPath appliedenhancements$calculationPath = OmniCalculationPath.AE2_NATIVE;

    @Unique
    private long appliedenhancements$requestedAmount;

    @Override
    public OmniCalculationPath molecularmanipulator$getCalculationPath() {
        return appliedenhancements$calculationPath;
    }

    @Override
    public void molecularmanipulator$setCalculationPath(OmniCalculationPath path) {
        appliedenhancements$calculationPath = java.util.Objects.requireNonNull(path, "path");
    }

    @Override
    public CraftingCalculationProgressSnapshot molecularmanipulator$getCalculationProgress() {
        return appliedenhancements$calculationProgress;
    }

    @Override
    public void molecularmanipulator$resetCalculationProgress() {
        appliedenhancements$calculationProgress = CraftingCalculationProgressSnapshot.idle();
    }

    @Override
    public boolean molecularmanipulator$acceptCalculationProgress(
            CraftingCalculationProgressSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        var current = appliedenhancements$calculationProgress;
        if (!CraftingProgressSnapshotOrder.isNewer(current, snapshot)) {
            return false;
        }
        boolean newGeneration = CraftingProgressSnapshotOrder.startsNewGeneration(
                current, snapshot);
        appliedenhancements$calculationProgress = snapshot;
        var menu = (CraftConfirmMenu) (Object) this;
        if (newGeneration) {
            menu.setPlan(null);
        }
        return true;
    }

    @Unique
    private CraftingProgressTaskBinding.Task appliedenhancements$startProgressTask(
            boolean progressDisplayEnabled) {
        CraftingCalculationProgressHandle progress = null;
        if (progressDisplayEnabled) {
            long generation = appliedenhancements$nextProgressGeneration.incrementAndGet();
            if (generation <= 0) {
                appliedenhancements$nextProgressGeneration.set(1);
                generation = 1;
            }
            progress = new CraftingCalculationProgressHandle(generation);
        }
        var task = appliedenhancements$progressBinding.begin(progress);
        appliedenhancements$progressRevision = 0;
        appliedenhancements$lastProgressSyncTick = Long.MIN_VALUE;
        appliedenhancements$terminalProgressSentGeneration = -1;
        appliedenhancements$calculationPath = OmniCalculationPath.AE2_NATIVE;
        return task;
    }

    @Unique
    private void appliedenhancements$cancelProgress() {
        appliedenhancements$progressBinding.clear();
    }

    @Inject(method = "planJob", at = @At("HEAD"))
    private void appliedenhancements$cancelProgressBeforePlan(
            AEKey what, int amount, CalculationStrategy strategy,
            CallbackInfoReturnable<Boolean> callback) {
        if (!((CraftConfirmMenu) (Object) this).isClientSide()) {
            appliedenhancements$requestedAmount = amount;
            appliedenhancements$calculationPath = OmniCalculationPath.AE2_NATIVE;
            appliedenhancements$cancelProgress();
        }
    }

    @Override
    public boolean appliedenhancements$planLong(
            AEKey what, long requestedAmount, CalculationStrategy strategy) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide() || what == null || requestedAmount <= 0 || strategy == null
                || !AppliedEnhancementsConfig.COMMON.enableLongRangeCrafting.get()) {
            return false;
        }
        long maximumAmount = Config.MAX_CRAFTING_ORDER_AMOUNT.get();
        if (NativeCraftingLongSafety.exceedsConfiguredLimit(requestedAmount, maximumAmount)) {
            menu.getPlayer().sendSystemMessage(Component.translatable(
                    "message.appliedenhancements.crafting_amount_too_large", maximumAmount));
            return false;
        }

        appliedenhancements$cancelProgress();
        if (this.job != null) {
            this.job.cancel(true);
        }

        var target = menu.getTarget();
        if (!(target instanceof IActionHost actionHost)) {
            return false;
        }
        IGridNode gridNode = actionHost.getActionableNode();
        if (gridNode == null) {
            return false;
        }
        IGrid grid = gridNode.getGrid();

        this.result = null;
        menu.setPlan(null);
        menu.clearError();
        this.whatToCraft = what;
        this.amount = (int) Math.min(requestedAmount, Integer.MAX_VALUE);
        this.appliedenhancements$requestedAmount = requestedAmount;
        this.appliedenhancements$calculationPath = OmniCalculationPath.AE2_NATIVE;

        ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {
            @Override
            public IActionSource getActionSource() {
                return new PlayerSource(menu.getPlayer(), actionHost);
            }

            @Override
            public IGridNode getGridNode() {
                return gridNode;
            }
        };

        var progressTask = appliedenhancements$startProgressTask(
                AppliedEnhancementsConfig.COMMON.enableProgressDisplay.get());
        var progress = progressTask.progress();
        ICraftingSimulationRequester effectiveRequester = requester;
        if (progress != null) {
            effectiveRequester = new CraftingCalculationProgressRequester(requester, progress);
        }
        try {
            Future<ICraftingPlan> rawJob = grid.getCraftingService().beginCraftingCalculation(
                    menu.getLevel(),
                    effectiveRequester,
                    what,
                    requestedAmount,
                    strategy);
            this.job = appliedenhancements$observeCalculationJob(
                    rawJob, progressTask, requestedAmount);
            return true;
        } catch (RuntimeException | Error failure) {
            appliedenhancements$finishFailedProgress(progressTask, failure);
            throw failure;
        }
    }

    @Inject(method = "replan", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$replanLong(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide() || appliedenhancements$requestedAmount <= Integer.MAX_VALUE
                || this.whatToCraft == null) {
            return;
        }
        if (!appliedenhancements$planLong(
                this.whatToCraft,
                appliedenhancements$requestedAmount,
                CalculationStrategy.CRAFT_LESS)) {
            menu.goBack();
        }
        callback.cancel();
    }

    @Inject(method = "goBack", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$returnToLongAmountScreen(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (!(menu.getPlayer() instanceof ServerPlayer player)
                || appliedenhancements$requestedAmount <= Integer.MAX_VALUE
                || this.whatToCraft == null
                || menu.getLocator() == null) {
            return;
        }

        menu.clearError();
        MenuOpener.open(CraftAmountMenu.TYPE, player, menu.getLocator());
        if (player.containerMenu instanceof CraftAmountMenu amountMenu
                && amountMenu instanceof LongCraftingAmountMenuBridge bridge) {
            bridge.appliedenhancements$setWhatToCraftLong(
                    this.whatToCraft, appliedenhancements$requestedAmount);
            amountMenu.broadcastChanges();
        }
        callback.cancel();
    }

    @WrapOperation(method = "planJob", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingService;beginCraftingCalculation(Lnet/minecraft/world/level/Level;Lappeng/api/networking/crafting/ICraftingSimulationRequester;Lappeng/api/stacks/AEKey;JLappeng/api/networking/crafting/CalculationStrategy;)Ljava/util/concurrent/Future;"))
    private Future<ICraftingPlan> appliedenhancements$trackCalculationProgress(
            ICraftingService craftingService,
            Level level,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
            Operation<Future<ICraftingPlan>> original) {
        var progressTask = appliedenhancements$startProgressTask(
                AppliedEnhancementsConfig.COMMON.enableProgressDisplay.get());
        var progress = progressTask.progress();
        ICraftingSimulationRequester effectiveRequester = requester;
        if (progress != null) {
            effectiveRequester = new CraftingCalculationProgressRequester(requester, progress);
        }
        try {
            Future<ICraftingPlan> rawJob = original.call(
                    craftingService,
                    level,
                    effectiveRequester,
                    what,
                    amount,
                    strategy);
            return appliedenhancements$observeCalculationJob(rawJob, progressTask, amount);
        } catch (RuntimeException | Error failure) {
            appliedenhancements$finishFailedProgress(progressTask, failure);
            throw failure;
        }
    }

    @Unique
    private Future<ICraftingPlan> appliedenhancements$observeCalculationJob(
            Future<ICraftingPlan> rawJob,
            CraftingProgressTaskBinding.Task progressTask,
            long requestedAmount) {
        Objects.requireNonNull(rawJob, "Crafting service returned a null planning future");
        return new TerminalAwareFuture<>(
                rawJob,
                NativeCraftingLongSafety::validatePlan,
                plan -> {
                    if (!appliedenhancements$progressBinding.isCurrent(progressTask)) {
                        return;
                    }
                    var progress = progressTask.progress();
                    OmniCalculationPath path = appliedenhancements$resolveCalculationPath(
                            plan, progress);
                    appliedenhancements$calculationPath = path;
                    if (progress != null) {
                        progress.complete(path);
                    }
                },
                failure -> {
                    if (!appliedenhancements$progressBinding.isCurrent(progressTask)) {
                        return;
                    }
                    appliedenhancements$finishFailedProgress(progressTask, failure);
                    var orderedChoiceRejection =
                            OmniOrderedChoicePlanningRejectedException.find(failure);
                    if (orderedChoiceRejection != null) {
                        ((CraftConfirmMenu) (Object) this).getPlayer().sendSystemMessage(
                                Component.translatable(
                                        "message.appliedenhancements.ordered_choice_native_too_large",
                                        orderedChoiceRejection.requestedItems(),
                                        orderedChoiceRejection.maxLinearNativeItems()));
                    } else if (NativeCraftingLongSafety.causedByUnsafeArithmetic(failure)) {
                        ((CraftConfirmMenu) (Object) this).getPlayer().sendSystemMessage(
                                Component.translatable(
                                        "message.appliedenhancements.crafting_amount_unsafe",
                                        requestedAmount));
                    }
                },
                () -> {
                    if (appliedenhancements$progressBinding.isCurrent(progressTask)
                            && progressTask.progress() != null) {
                        progressTask.progress().cancel();
                    }
                });
    }

    @Unique
    private void appliedenhancements$finishFailedProgress(
            CraftingProgressTaskBinding.Task progressTask, Throwable failure) {
        if (!appliedenhancements$progressBinding.isCurrent(progressTask)) {
            return;
        }
        var progress = progressTask.progress();
        if (progress == null) {
            return;
        }
        if (appliedenhancements$isCancellation(failure)) {
            progress.cancel();
        } else {
            progress.fail();
        }
    }

    @Unique
    private static boolean appliedenhancements$isCancellation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CancellationException
                    || current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private OmniCalculationPath appliedenhancements$resolveCalculationPath(
            ICraftingPlan plan, CraftingCalculationProgressHandle progress) {
        OmniCalculationPath carriedPath = plan instanceof OmniCalculationPathCarrier carrier
                ? carrier.molecularmanipulator$getCalculationPath()
                : null;
        if (carriedPath != null && carriedPath != OmniCalculationPath.AE2_NATIVE) {
            return carriedPath;
        }
        if (plan != null
                && plan.getClass().getName().toLowerCase(Locale.ROOT).contains("ecoae")) {
            return OmniCalculationPath.ECOAE;
        }
        if (progress != null && !progress.terminal()
                && ModList.get().isLoaded("neoecoae")) {
            return OmniCalculationPath.ECOAE;
        }
        return appliedenhancements$calculationPath;
    }

    @Inject(method = "broadcastChanges", at = @At(value = "INVOKE",
            target = "Lappeng/menu/me/crafting/CraftConfirmMenu;sendPacketToClient(Lappeng/core/network/ClientboundPacket;)V"))
    private void appliedenhancements$syncCalculationPath(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide()
                || this.result == null
                || !(menu.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        var path = appliedenhancements$resolveCalculationPath(
                this.result, appliedenhancements$progressBinding.currentProgress());
        PacketDistributor.sendToPlayer(
                player,
                new CraftingCalculationPathPayload(menu.containerId, path));
        appliedenhancements$calculationPath = path;
        appliedenhancements$sendCalculationProgress(player, menu);
    }

    @Inject(method = "broadcastChanges", at = @At("RETURN"))
    private void appliedenhancements$syncCalculationProgress(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        var progress = appliedenhancements$progressBinding.currentProgress();
        if (!AppliedEnhancementsConfig.COMMON.enableProgressDisplay.get()
                || menu.isClientSide()
                || progress == null
                || !(menu.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        if (player.serverLevel().getGameTime() == appliedenhancements$lastProgressSyncTick) {
            return;
        }
        appliedenhancements$sendCalculationProgress(player, menu);
    }

    @Unique
    private void appliedenhancements$sendCalculationProgress(
            ServerPlayer player, CraftConfirmMenu menu) {
        var progress = appliedenhancements$progressBinding.currentProgress();
        if (!AppliedEnhancementsConfig.COMMON.enableProgressDisplay.get()
                || progress == null
                || progress.terminal()
                && appliedenhancements$terminalProgressSentGeneration
                        == progress.generation()) {
            return;
        }

        appliedenhancements$lastProgressSyncTick = player.serverLevel().getGameTime();
        var snapshot = progress.snapshot(++appliedenhancements$progressRevision);
        PacketDistributor.sendToPlayer(
                player,
                new CraftingCalculationProgressPayload(menu.containerId, snapshot));
        if (snapshot.phase().terminal()) {
            appliedenhancements$terminalProgressSentGeneration = snapshot.generation();
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void appliedenhancements$cancelProgressWhenClosed(
            net.minecraft.world.entity.player.Player player,
            CallbackInfo callback) {
        if (!((CraftConfirmMenu) (Object) this).isClientSide()) {
            appliedenhancements$cancelProgress();
        }
    }
}
