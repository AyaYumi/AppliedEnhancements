package com.appliedenhancements.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
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
import com.appliedenhancements.runtime.ManualCraftingInventoryLock;
import com.appliedenhancements.runtime.TerminalAwareFuture;
import com.github.appliedenhancements.crafting.aelis.AelisOrderedChoicePlanningRejectedException;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressRequester;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.network.CraftingCalculationPathPayload;
import com.github.appliedenhancements.network.CraftingCalculationProgressPayload;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuMixin implements CraftingCalculationProgressMenuBridge,
        LongCraftingConfirmMenuBridge, AelisCalculationPathMenuBridge {
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
    private AelisCalculationPath appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;

    @Unique
    private Map<AEKey, Long> appliedenhancements$cyclicCraftAmounts = Map.of();

    @Unique
    private long appliedenhancements$requestedAmount;

    @Unique
    private CalculationStrategy appliedenhancements$calculationStrategy =
            CalculationStrategy.REPORT_MISSING_ITEMS;

    @Unique
    private ManualCraftingInventoryLock.Reservation appliedenhancements$inventoryReservation;

    @Unique
    private ICraftingPlan appliedenhancements$reservedPlan;

    @Override
    public AelisCalculationPath molecularmanipulator$getCalculationPath() {
        return appliedenhancements$calculationPath;
    }

    @Override
    public void molecularmanipulator$setCalculationPath(AelisCalculationPath path) {
        appliedenhancements$calculationPath = java.util.Objects.requireNonNull(path, "path");
    }

    @Override
    public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() {
        return appliedenhancements$cyclicCraftAmounts;
    }

    @Override
    public void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> amounts) {
        appliedenhancements$cyclicCraftAmounts = Map.copyOf(
                Objects.requireNonNull(amounts, "amounts"));
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
        appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
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
            appliedenhancements$calculationStrategy = strategy;
            appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
            appliedenhancements$cyclicCraftAmounts = Map.of();
            appliedenhancements$releaseInventoryReservation();
            appliedenhancements$cancelProgress();
        }
    }

    @Override
    public boolean appliedenhancements$planLong(
            AEKey what, long requestedAmount, CalculationStrategy strategy) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide() || what == null || requestedAmount <= 0 || strategy == null
                || !Config.ENABLE_LONG_RANGE_CRAFTING.get()) {
            return false;
        }
        long maximumAmount = Config.MAX_CRAFTING_ORDER_AMOUNT.get();
        if (NativeCraftingLongSafety.exceedsConfiguredLimit(requestedAmount, maximumAmount)) {
            menu.getPlayer().sendSystemMessage(Component.translatable(
                    "message.appliedenhancements.crafting_amount_too_large", maximumAmount));
            return false;
        }

        appliedenhancements$cancelProgress();
        appliedenhancements$releaseInventoryReservation();
        appliedenhancements$calculationStrategy = strategy;
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
        this.appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
        this.appliedenhancements$cyclicCraftAmounts = Map.of();

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
                Config.ENABLE_PROGRESS_DISPLAY.get());
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
        if (!menu.isClientSide()) {
            appliedenhancements$releaseInventoryReservation();
        }
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
                Config.ENABLE_PROGRESS_DISPLAY.get());
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
                    AelisCalculationPath path = appliedenhancements$resolveCalculationPath(plan);
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
                            AelisOrderedChoicePlanningRejectedException.find(failure);
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
    private AelisCalculationPath appliedenhancements$resolveCalculationPath(ICraftingPlan plan) {
        AelisCalculationPath carriedPath = plan instanceof AelisCalculationPathCarrier carrier
                ? carrier.molecularmanipulator$getCalculationPath()
                : null;
        if (carriedPath != null && carriedPath != AelisCalculationPath.AE2_NATIVE) {
            return carriedPath;
        }
        if (plan != null && !(plan instanceof CraftingPlan)) {
            return AelisCalculationPath.EXTERNAL;
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

        var path = appliedenhancements$resolveCalculationPath(this.result);
        Map<AEKey, Long> cyclicCraftAmounts =
                this.result instanceof AelisCyclicCraftAmountsCarrier carrier
                        ? carrier.appliedenhancements$getCyclicCraftAmounts()
                        : Map.of();
        PacketDistributor.sendToPlayer(
                player,
                new CraftingCalculationPathPayload(
                        menu.containerId, path, cyclicCraftAmounts));
        appliedenhancements$calculationPath = path;
        appliedenhancements$cyclicCraftAmounts = cyclicCraftAmounts;
        appliedenhancements$sendCalculationProgress(player, menu);
    }

    @Inject(method = "broadcastChanges", at = @At("RETURN"))
    private void appliedenhancements$syncCalculationProgress(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        var progress = appliedenhancements$progressBinding.currentProgress();
        if (!Config.ENABLE_PROGRESS_DISPLAY.get()
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

    @Inject(method = "broadcastChanges", at = @At("RETURN"))
    private void appliedenhancements$reserveCompletedPlanInventory(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide()) {
            return;
        }
        if (!ManualCraftingInventoryLock.enabled()) {
            appliedenhancements$releaseInventoryReservation();
            return;
        }
        // Auto-start can replace the menu before broadcastChanges returns.
        if (menu.getPlayer().containerMenu != menu || this.result == null
                || this.result == appliedenhancements$reservedPlan) {
            return;
        }
        if (!appliedenhancements$tryReserveInventory(this.result)) {
            appliedenhancements$restartAfterReservationConflict();
        }
    }

    @WrapOperation(method = "startJob", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingService;submitJob(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/IActionSource;)Lappeng/api/networking/crafting/ICraftingSubmitResult;"))
    private ICraftingSubmitResult appliedenhancements$submitWithReservedInventory(
            ICraftingService craftingService,
            ICraftingPlan plan,
            ICraftingRequester requester,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource source,
            Operation<ICraftingSubmitResult> original) {
        if (!ManualCraftingInventoryLock.enabled()) {
            appliedenhancements$releaseInventoryReservation();
        }
        var reservation = plan == appliedenhancements$reservedPlan
                ? appliedenhancements$inventoryReservation
                : null;
        try {
            ICraftingSubmitResult submitResult = reservation == null
                    ? original.call(
                            craftingService, plan, requester, target, prioritizePower, source)
                    : reservation.submit(() -> original.call(
                            craftingService, plan, requester, target, prioritizePower, source));
            if (submitResult.successful()) {
                appliedenhancements$releaseInventoryReservation();
            }
            return submitResult;
        } catch (RuntimeException | Error failure) {
            appliedenhancements$releaseInventoryReservation();
            throw failure;
        }
    }

    @Unique
    private boolean appliedenhancements$tryReserveInventory(ICraftingPlan plan) {
        appliedenhancements$releaseInventoryReservation();
        IGrid grid = appliedenhancements$getCurrentGrid();
        if (grid == null || plan == null) {
            return false;
        }
        var menu = (CraftConfirmMenu) (Object) this;
        var reservation = ManualCraftingInventoryLock.tryAcquire(
                grid.getStorageService().getInventory(),
                plan.usedItems(),
                new PlayerSource(menu.getPlayer(), (IActionHost) menu.getTarget()));
        if (reservation == null) {
            return false;
        }
        appliedenhancements$inventoryReservation = reservation;
        appliedenhancements$reservedPlan = plan;
        return true;
    }

    @Unique
    private void appliedenhancements$restartAfterReservationConflict() {
        var menu = (CraftConfirmMenu) (Object) this;
        this.result = null;
        menu.setPlan(null);
        boolean replanned = this.whatToCraft != null
                && (appliedenhancements$requestedAmount > Integer.MAX_VALUE
                        ? appliedenhancements$planLong(
                                this.whatToCraft,
                                appliedenhancements$requestedAmount,
                                appliedenhancements$calculationStrategy)
                        : menu.planJob(
                                this.whatToCraft,
                                (int) appliedenhancements$requestedAmount,
                                appliedenhancements$calculationStrategy));
        if (!replanned) {
            menu.goBack();
        }
    }

    @Unique
    private IGrid appliedenhancements$getCurrentGrid() {
        var menu = (CraftConfirmMenu) (Object) this;
        if (!(menu.getTarget() instanceof IActionHost actionHost)) {
            return null;
        }
        IGridNode node = actionHost.getActionableNode();
        return node == null ? null : node.getGrid();
    }

    @Unique
    private void appliedenhancements$releaseInventoryReservation() {
        if (appliedenhancements$inventoryReservation != null) {
            appliedenhancements$inventoryReservation.close();
            appliedenhancements$inventoryReservation = null;
        }
        appliedenhancements$reservedPlan = null;
    }

    @Unique
    private void appliedenhancements$sendCalculationProgress(
            ServerPlayer player, CraftConfirmMenu menu) {
        var progress = appliedenhancements$progressBinding.currentProgress();
        if (!Config.ENABLE_PROGRESS_DISPLAY.get()
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
            appliedenhancements$releaseInventoryReservation();
            appliedenhancements$cancelProgress();
        }
    }
}
