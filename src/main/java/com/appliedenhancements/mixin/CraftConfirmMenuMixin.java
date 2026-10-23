package com.appliedenhancements.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
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
import com.appliedenhancements.runtime.NativeCraftingMenuCompat;
import com.appliedenhancements.ae2.LongCraftingAmountMenuBridge;
import com.appliedenhancements.ae2.LongCraftingConfirmMenuBridge;
import com.appliedenhancements.Config;
import com.appliedenhancements.runtime.CraftingProgressSnapshotOrder;
import com.appliedenhancements.runtime.CraftingProgressTaskBinding;
import com.appliedenhancements.runtime.DataEnergisticsMenuCompat;
import com.appliedenhancements.runtime.ManualCraftingInventoryLock;
import com.appliedenhancements.runtime.TerminalAwareFuture;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressRequester;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.network.CraftingCalculationPathPayload;
import com.github.appliedenhancements.network.CraftingCalculationProgressPayload;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import com.appliedenhancements.network.NetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;
import java.util.Map;
import java.math.BigInteger;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuMixin implements CraftingCalculationProgressMenuBridge,
        LongCraftingConfirmMenuBridge, AelisCalculationPathMenuBridge, com.appliedenhancements.ae2.ExactCraftingMenuBridge,
        com.appliedenhancements.ae2.ForceCraftingMenuBridge,
        com.appliedenhancements.ae2.AelisPlanGraphMenuBridge {
    @Unique private com.appliedenhancements.plan.AelisPlanGraph appliedenhancements$planGraph;
    @Unique private com.appliedenhancements.plan.AelisPlanGraph appliedenhancements$cachedGraph;
    @Unique private ICraftingPlan appliedenhancements$graphSource;
    @Unique private appeng.menu.me.crafting.CraftingPlanSummary appliedenhancements$graphBinding;
    @Unique private int appliedenhancements$graphRequest;
    @Unique private long appliedenhancements$graphRetryAt;
    @Unique private long appliedenhancements$lastGraphResponseTick = Long.MIN_VALUE;
    @Unique private ICraftingPlan appliedenhancements$forcedRetryPlan;

    @Override
    public com.appliedenhancements.plan.AelisPlanGraph appliedenhancements$getPlanGraph() {
        return appliedenhancements$graphBinding == this.plan ? appliedenhancements$planGraph : null;
    }

    @Override
    public void appliedenhancements$requestPlanGraph() {
        var menu = (CraftConfirmMenu) (Object) this;
        if (!menu.isClientSide() || this.plan == null) return;
        long now = System.nanoTime();
        if (appliedenhancements$graphBinding == this.plan
                && (appliedenhancements$planGraph != null || now < appliedenhancements$graphRetryAt)) return;
        if (appliedenhancements$graphBinding != this.plan) {
            appliedenhancements$graphRequest = appliedenhancements$graphRequest == Integer.MAX_VALUE
                    ? 1 : appliedenhancements$graphRequest + 1;
        }
        appliedenhancements$graphBinding = this.plan;
        appliedenhancements$planGraph = null;
        appliedenhancements$graphRetryAt = now + 1_000_000_000L;
        // Retries keep the same ID so a slow valid response can still satisfy this plan.
        NetworkHandler.sendToServer(new com.appliedenhancements.network.AelisPlanGraphRequest(
                menu.containerId, appliedenhancements$graphRequest));
    }

    @Override
    public void appliedenhancements$acceptPlanGraph(int request, com.appliedenhancements.plan.AelisPlanGraph graph) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide() && request == appliedenhancements$graphRequest
                && this.plan != null && appliedenhancements$graphBinding == this.plan) {
            appliedenhancements$planGraph = graph;
        }
    }

    @Override
    public void appliedenhancements$sendPlanGraph(int request) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (!(menu.getPlayer() instanceof ServerPlayer player) || player.containerMenu != menu
                || !menu.stillValid(player) || this.result == null
                || appliedenhancements$resolveCalculationPath(this.result) != AelisCalculationPath.AELIS) return;
        long tick = player.serverLevel().getGameTime();
        if (appliedenhancements$graphSource == this.result
                && appliedenhancements$lastGraphResponseTick != Long.MIN_VALUE
                && tick - appliedenhancements$lastGraphResponseTick < 10) return;
        appliedenhancements$lastGraphResponseTick = tick;
        if (appliedenhancements$graphSource != this.result) {
            appliedenhancements$graphSource = this.result;
            try {
                appliedenhancements$cachedGraph = com.appliedenhancements.runtime.AelisPlanGraphBuilder.build(this.result);
                // AE2 15 serializes keys through FriendlyByteBuf and NBT, without a registry buffer.
                var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    com.appliedenhancements.network.AelisPlanGraphPayload.STREAM_CODEC.encode(buffer,
                            new com.appliedenhancements.network.AelisPlanGraphPayload(menu.containerId, request,
                                    appliedenhancements$cachedGraph));
                } finally {
                    buffer.release();
                }
            } catch (RuntimeException | LinkageError unavailable) {
                appliedenhancements$cachedGraph = new com.appliedenhancements.plan.AelisPlanGraph(
                        java.util.List.of(), java.util.List.of(), -1, false);
                com.appliedenhancements.runtime.AelisPlanningLog.warning("plan_graph",
                        "Could not read the optional crafting dependency graph; original plan retained", unavailable);
            }
        }
        NetworkHandler.sendToPlayer(player, new com.appliedenhancements.network.AelisPlanGraphPayload(
                menu.containerId, request, appliedenhancements$cachedGraph));
    }
    @Unique private BigInteger appliedenhancements$exactRequestedAmount;

    @Override public boolean appliedenhancements$planExact(AEKey what, BigInteger requested) {
        var request = new com.appliedenhancements.api.AelisExactRequest(requested);
        if (!Config.MAX_CRAFTING_ORDER_AMOUNT.get().supportsBigInteger()) return false;
        return appliedenhancements$planRequested(what, request.projection(), CalculationStrategy.REPORT_MISSING_ITEMS, requested);
    }
    @Unique
    private static final AtomicLong appliedenhancements$nextProgressGeneration = new AtomicLong();

    /** How long a confirm menu may stay open without any planning job before it is closed. */
    @Unique
    private static final int APPLIEDENHANCEMENTS_STALLED_PLAN_TICKS = 100;

    @Shadow
    private ICraftingPlan result;

    @Shadow
    private AEKey whatToCraft;

    @Shadow
    private Future<ICraftingPlan> job;

    @Shadow
    private appeng.menu.me.crafting.CraftingPlanSummary plan;

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
    @Unique private Map<AEKey, BigInteger> appliedenhancements$bigIntegerCyclicCraftAmounts = Map.of();
    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCyclicCraftAmounts() {
        return appliedenhancements$bigIntegerCyclicCraftAmounts;
    }
    @Override public void appliedenhancements$setBigIntegerCyclicCraftAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerCyclicCraftAmounts =
                com.github.appliedenhancements.crafting.aelis.AelisCyclicCraftingAmounts.exactAmounts(
                        appliedenhancements$cyclicCraftAmounts, amounts);
    }

    @Unique
    private Map<AEKey, BigInteger> appliedenhancements$bigIntegerCraftAmounts = Map.of();
    @Unique private Map<AEKey, BigInteger> appliedenhancements$bigIntegerMissingAmounts = Map.of();
    @Unique private Map<AEKey, BigInteger> appliedenhancements$bigIntegerStoredAmounts = Map.of();
    @Unique private BigInteger appliedenhancements$bigIntegerBytes;
    @Unique private BigInteger appliedenhancements$bigIntegerFinalAmount;
    @Override public BigInteger appliedenhancements$getBigIntegerFinalAmount() { return appliedenhancements$bigIntegerFinalAmount; }
    @Override public void appliedenhancements$setBigIntegerFinalAmount(BigInteger value) { appliedenhancements$bigIntegerFinalAmount = value; }
    @Override public BigInteger appliedenhancements$getBigIntegerBytes() { return appliedenhancements$bigIntegerBytes; }
    @Override public void appliedenhancements$setBigIntegerBytes(BigInteger bytes) { appliedenhancements$bigIntegerBytes = bytes; }
    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerStoredAmounts() {
        return appliedenhancements$bigIntegerStoredAmounts;
    }
    @Override public void appliedenhancements$setBigIntegerStoredAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerStoredAmounts = Map.copyOf(amounts);
    }

    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() {
        return appliedenhancements$bigIntegerMissingAmounts;
    }

    @Override public void appliedenhancements$setBigIntegerMissingAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerMissingAmounts = Map.copyOf(amounts);
    }

    @Unique
    private long appliedenhancements$requestedAmount;

    @Unique
    private CalculationStrategy appliedenhancements$calculationStrategy =
            CalculationStrategy.REPORT_MISSING_ITEMS;

    @Unique
    private ManualCraftingInventoryLock.Reservation appliedenhancements$inventoryReservation;

    @Unique
    private ICraftingPlan appliedenhancements$reservedPlan;

    @Unique
    private int appliedenhancements$reservationConflictReplans;

    @Unique
    private boolean appliedenhancements$replanningAfterReservationConflict;

    /** Server ticks this menu spent without a planning job and without a plan. */
    @Unique
    private int appliedenhancements$idleTicksWithoutPlanning;

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
        appliedenhancements$bigIntegerCyclicCraftAmounts =
                com.github.appliedenhancements.crafting.aelis.AelisCyclicCraftingAmounts.exactAmounts(
                        appliedenhancements$cyclicCraftAmounts, Map.of());
    }

    @Override
    public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() {
        return appliedenhancements$bigIntegerCraftAmounts;
    }

    @Override
    public void appliedenhancements$setBigIntegerCraftAmounts(
            Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerCraftAmounts = Map.copyOf(
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
        if (newGeneration) {
            appliedenhancements$clearDisplayedPlan();
        }
        return true;
    }

    @Unique
    private CraftingProgressTaskBinding.Task appliedenhancements$startProgressTask(
            boolean progressDisplayEnabled) {
        long generation = appliedenhancements$nextProgressGeneration.incrementAndGet();
        if (generation <= 0) {
            appliedenhancements$nextProgressGeneration.set(1);
            generation = 1;
        }
        var initialProgress = new CraftingCalculationProgressHandle(generation);
        var task = appliedenhancements$progressBinding.begin(progressDisplayEnabled ? initialProgress : null);
        // A new generation invalidates the previous summary even with detailed progress disabled.
        // Send this before the calculation can complete and publish its replacement plan.
        var menu = (CraftConfirmMenu) (Object) this;
        if (!menu.isClientSide() && menu.getPlayer() instanceof ServerPlayer player) {
            NetworkHandler.sendToPlayer(player,
                    new CraftingCalculationProgressPayload(menu.containerId, initialProgress.snapshot(1)));
        }
        appliedenhancements$progressRevision = 1;
        appliedenhancements$lastProgressSyncTick = Long.MIN_VALUE;
        appliedenhancements$terminalProgressSentGeneration = -1;
        appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
        return task;
    }

    @Unique
    private void appliedenhancements$cancelProgress() {
        appliedenhancements$progressBinding.clear();
    }

    /**
     * Clears the plan displayed by this confirmation menu without calling
     * {@code CraftConfirmMenu#setPlan(CraftingPlanSummary)} with {@code null}.
     *
     * <p>Third-party mixins may inject into {@code setPlan} and dereference its
     * argument, for example AE2 Crafting Time 1.2.5 iterates
     * {@code plan.getEntries()} without a null check. Passing {@code null} makes
     * such a listener throw while the menu is about to start planning, which
     * aborts the whole plan request (the exception is swallowed by the packet
     * handler) and leaves the screen on "calculating" forever. Writing the field
     * keeps AE2's own behaviour while skipping those listeners.</p>
     */
    @Unique
    private void appliedenhancements$clearDisplayedPlan() {
        appliedenhancements$planGraph = null;
        appliedenhancements$graphBinding = null;
        appliedenhancements$graphRetryAt = 0;
        appliedenhancements$forcedRetryPlan = null;
        appliedenhancements$cyclicCraftAmounts = Map.of();
        appliedenhancements$bigIntegerCyclicCraftAmounts = Map.of();
        appliedenhancements$bigIntegerCraftAmounts = Map.of();
        appliedenhancements$bigIntegerMissingAmounts = Map.of();
        appliedenhancements$bigIntegerStoredAmounts = Map.of();
        appliedenhancements$bigIntegerBytes = null;
        appliedenhancements$bigIntegerFinalAmount = null;
        if (this.plan != null) {
            this.plan = null;
        }
    }

    // Forge's runtime remapper may resolve a missing int/long overload by name.
    // Callback-only injection works for both upstream and UELM signatures.
    @Inject(method = "planJob", at = @At("HEAD"))
    private void appliedenhancements$beforeNativePlan(CallbackInfoReturnable<Boolean> callback) {
        appliedenhancements$cancelProgressBeforePlan(0, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Unique
    private void appliedenhancements$cancelProgressBeforePlan(long amount, CalculationStrategy strategy) {
        if (!((CraftConfirmMenu) (Object) this).isClientSide()) {
            appliedenhancements$clearDisplayedPlan();
            if (!appliedenhancements$replanningAfterReservationConflict) {
                appliedenhancements$reservationConflictReplans = 0;
            }
            appliedenhancements$requestedAmount = amount;
            appliedenhancements$exactRequestedAmount = null;
            appliedenhancements$calculationStrategy = strategy;
            appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
            appliedenhancements$cyclicCraftAmounts = Map.of();
            appliedenhancements$bigIntegerCyclicCraftAmounts = Map.of();
            appliedenhancements$bigIntegerCraftAmounts = Map.of();
            appliedenhancements$bigIntegerMissingAmounts = Map.of();
            appliedenhancements$releaseInventoryReservation();
            appliedenhancements$cancelProgress();
        }
    }

    @Override
    public boolean appliedenhancements$planLong(
            AEKey what, long requestedAmount, CalculationStrategy strategy) {
        return appliedenhancements$planRequested(what, requestedAmount, strategy, null);
    }

    @Unique private boolean appliedenhancements$planRequested(AEKey what, long requestedAmount,
            CalculationStrategy strategy, BigInteger exact) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide() || what == null || requestedAmount <= 0 || strategy == null
                || !Config.MAX_CRAFTING_ORDER_AMOUNT.get().isEnabled()) {
            return false;
        }
        if (!appliedenhancements$replanningAfterReservationConflict) {
            appliedenhancements$reservationConflictReplans = 0;
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
        appliedenhancements$clearDisplayedPlan();
        menu.clearError();
        this.whatToCraft = what;
        NativeCraftingMenuCompat.setAmount(menu, requestedAmount);
        this.appliedenhancements$requestedAmount = requestedAmount;
        this.appliedenhancements$exactRequestedAmount = exact;
        this.appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
        this.appliedenhancements$cyclicCraftAmounts = Map.of();
        this.appliedenhancements$bigIntegerCyclicCraftAmounts = Map.of();

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
            Future<ICraftingPlan> rawJob = exact != null
                    ? com.appliedenhancements.api.AelisExactCraftingService.begin(menu.getLevel(), effectiveRequester,
                            new com.appliedenhancements.api.AelisCraftingRequest(what, exact, strategy))
                    : grid.getCraftingService().beginCraftingCalculation(
                    menu.getLevel(),
                    effectiveRequester,
                    what,
                    requestedAmount,
                    strategy);
            this.job = appliedenhancements$observeCalculationJob(rawJob, progressTask);
            return true;
        } catch (RuntimeException | Error failure) {
            appliedenhancements$finishFailedProgress(progressTask, failure);
            throw failure;
        }
    }

    @Inject(method = "replan", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$replanLong(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (!menu.isClientSide() && !appliedenhancements$replanningAfterReservationConflict) {
            appliedenhancements$reservationConflictReplans = 0;
        }
        if (!menu.isClientSide() && appliedenhancements$exactRequestedAmount != null && whatToCraft != null) {
            if (!appliedenhancements$planRequested(whatToCraft,
                    new com.appliedenhancements.api.AelisExactRequest(appliedenhancements$exactRequestedAmount).projection(),
                    CalculationStrategy.CRAFT_LESS, appliedenhancements$exactRequestedAmount)) menu.goBack();
            callback.cancel(); return;
        }
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
        long requestedAmount = appliedenhancements$effectiveRequestedAmount(menu);
        if (!(menu.getPlayer() instanceof ServerPlayer player)
                || requestedAmount <= Integer.MAX_VALUE
                || this.whatToCraft == null
                || menu.getLocator() == null) {
            return;
        }

        menu.clearError();
        MenuOpener.open(CraftAmountMenu.TYPE, player, menu.getLocator());
        if (player.containerMenu instanceof CraftAmountMenu amountMenu
                && amountMenu instanceof LongCraftingAmountMenuBridge bridge) {
            bridge.appliedenhancements$setWhatToCraftLong(
                    this.whatToCraft, requestedAmount);
            DataEnergisticsMenuCompat.restoreAmountScreen(
                    menu, amountMenu, requestedAmount);
            amountMenu.broadcastChanges();
            NetworkHandler.sendToPlayer(player, new com.appliedenhancements.network.ExactCraftingAmountSyncPayload(
                    amountMenu.containerId, appliedenhancements$exactRequestedAmount != null
                            ? appliedenhancements$exactRequestedAmount.toString() : Long.toString(requestedAmount)));
        }
        callback.cancel();
    }

    @Unique
    private long appliedenhancements$effectiveRequestedAmount(CraftConfirmMenu menu) {
        if (appliedenhancements$requestedAmount > 0) {
            return appliedenhancements$requestedAmount;
        }
        return DataEnergisticsMenuCompat.requestedAmount(menu)
                .orElse(Math.max(0L, NativeCraftingMenuCompat.amount(menu)));
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
        appliedenhancements$requestedAmount = amount;
        appliedenhancements$calculationStrategy = strategy;
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
            return appliedenhancements$observeCalculationJob(rawJob, progressTask);
        } catch (RuntimeException | Error failure) {
            appliedenhancements$finishFailedProgress(progressTask, failure);
            throw failure;
        }
    }

    @Unique
    private Future<ICraftingPlan> appliedenhancements$observeCalculationJob(
            Future<ICraftingPlan> rawJob,
            CraftingProgressTaskBinding.Task progressTask) {
        Objects.requireNonNull(rawJob, "Crafting service returned a null planning future");
        return new TerminalAwareFuture<>(
                rawJob,
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

    @Inject(method = { "broadcastChanges", "m_38946_" }, at = @At(value = "INVOKE",
            target = "Lappeng/menu/me/crafting/CraftConfirmMenu;sendPacketToClient(Lappeng/core/sync/BasePacket;)V"))
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
        Map<AEKey, BigInteger> bigIntegerCyclicCraftAmounts =
                com.github.appliedenhancements.crafting.aelis.AelisCyclicCraftingAmounts.overflowingAmounts(
                        com.appliedenhancements.api.AelisCycleExecutionApi.getExactCyclicCraftAmounts(this.result));
        Map<AEKey, BigInteger> bigIntegerCraftAmounts =
                this.result instanceof AelisBigIntegerCraftAmountsCarrier carrier
                        ? carrier.appliedenhancements$getBigIntegerCraftAmounts()
                        : Map.of();
        Map<AEKey, BigInteger> bigIntegerMissingAmounts =
                this.result instanceof AelisBigIntegerCraftAmountsCarrier carrier
                        ? carrier.appliedenhancements$getBigIntegerMissingAmounts() : Map.of();
        Map<AEKey, BigInteger> bigIntegerStoredAmounts =
                this.result instanceof AelisBigIntegerCraftAmountsCarrier carrier
                        ? carrier.appliedenhancements$getBigIntegerStoredAmounts() : Map.of();
        BigInteger bigIntegerBytes = this.result instanceof AelisBigIntegerCraftAmountsCarrier carrier
                ? carrier.appliedenhancements$getBigIntegerBytes() : null;
        NetworkHandler.sendToPlayer(
                player,
                new CraftingCalculationPathPayload(
                        menu.containerId, path, cyclicCraftAmounts,
                        bigIntegerCraftAmounts, bigIntegerMissingAmounts, bigIntegerStoredAmounts, bigIntegerBytes,
                        com.appliedenhancements.api.AelisExactCraftingPlanApi.getFinalOutputAmount(result),
                        bigIntegerCyclicCraftAmounts));
        appliedenhancements$calculationPath = path;
        appliedenhancements$cyclicCraftAmounts = cyclicCraftAmounts;
        appliedenhancements$setBigIntegerCyclicCraftAmounts(bigIntegerCyclicCraftAmounts);
        appliedenhancements$bigIntegerCraftAmounts = bigIntegerCraftAmounts;
        appliedenhancements$bigIntegerMissingAmounts = bigIntegerMissingAmounts;
        appliedenhancements$bigIntegerStoredAmounts = bigIntegerStoredAmounts;
        appliedenhancements$bigIntegerBytes = bigIntegerBytes;
        appliedenhancements$sendCalculationProgress(player, menu);
    }

    @Inject(method = { "broadcastChanges", "m_38946_" }, at = @At("RETURN"))
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

    @Inject(method = { "broadcastChanges", "m_38946_" }, at = @At("RETURN"))
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

    @Override
    public void appliedenhancements$startForced() {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide()) {
            NetworkHandler.sendToServer(new com.appliedenhancements.network.ForceCraftingStartPayload(menu.containerId));
            return;
        }
        if (menu.getPlayer().containerMenu != menu || !menu.stillValid(menu.getPlayer())
                || appliedenhancements$calculationPath != AelisCalculationPath.AELIS
                || this.result == null || appliedenhancements$getCurrentGrid() == null) return;
        ICraftingPlan original = this.result;
        final ICraftingPlan forced;
        try {
            forced = com.appliedenhancements.runtime.AelisForcedPlan.prepare(original);
        } catch (ArithmeticException unsupportedAmount) {
            menu.getPlayer().displayClientMessage(Component.translatable(
                    "gui.appliedenhancements.plan.force_amount_too_large"), false);
            return;
        }
        appliedenhancements$forcedRetryPlan = original;
        this.result = forced;
        if (appliedenhancements$reservedPlan == original) appliedenhancements$reservedPlan = forced;
        try {
            // Both upstream AE2 and UELM expose startJob(); retain their native CPU checks.
            menu.startJob();
        } finally {
            if (this.result == forced) this.result = original;
            if (appliedenhancements$reservedPlan == forced) appliedenhancements$reservedPlan = original;
        }
    }

    // Regex selectors retain the descriptor during Forge's permissive remap
    // fallback; an absent overload must never bind to the other signature.
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(
            method = "name=/^startJob$/ desc=/^\\(\\)V$/", require = 0)
    private void appliedenhancements$ownReservationThroughoutStart(Operation<Void> original) {
        if (this.result != null && this.result == appliedenhancements$forcedRetryPlan && this.result.simulation()) {
            appliedenhancements$startForced();
            return;
        }
        appliedenhancements$startWithReservation(() -> original.call());
    }

    // UELM's allow-missing overload submits through a concrete service and an
    // additional enum. Protect the entire native call instead of its descriptor.
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(
            method = "name=/^startJob$/ desc=/^\\(Z\\)V$/", require = 0)
    private void appliedenhancements$ownReservationThroughoutUelmStart(boolean allowMissing, Operation<Void> original) {
        if (this.result != null && this.result == appliedenhancements$forcedRetryPlan && this.result.simulation()) {
            appliedenhancements$startForced();
            return;
        }
        appliedenhancements$startWithReservation(() -> original.call(allowMissing));
    }

    @Unique
    private void appliedenhancements$startWithReservation(Runnable start) {
        if (!ManualCraftingInventoryLock.enabled()) {
            appliedenhancements$releaseInventoryReservation();
        }
        var reservation = this.result == appliedenhancements$reservedPlan
                ? appliedenhancements$inventoryReservation
                : null;
        try {
            if (reservation == null) start.run();
            else reservation.submit(() -> { start.run(); return null; });
        } catch (RuntimeException | Error failure) {
            appliedenhancements$releaseInventoryReservation();
            throw failure;
        }
    }

    @WrapOperation(method = "name=/^startJob$/ desc=/^\\(Z?\\)V$/", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingSubmitResult;successful()Z"))
    private boolean appliedenhancements$releaseSuccessfulReservation(ICraftingSubmitResult result, Operation<Boolean> original) {
        boolean successful = original.call(result);
        if (successful) {
            appliedenhancements$forcedRetryPlan = null;
            appliedenhancements$releaseInventoryReservation();
        }
        return successful;
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
        if (++appliedenhancements$reservationConflictReplans > 1) {
            if (menu.getPlayer() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable(
                        "message.appliedenhancements.crafting_reservation_conflict"));
            }
            menu.goBack();
            return;
        }

        this.result = null;
        appliedenhancements$clearDisplayedPlan();
        boolean replanned;
        appliedenhancements$replanningAfterReservationConflict = true;
        try {
            replanned = this.whatToCraft != null
                    && (appliedenhancements$exactRequestedAmount != null
                            ? appliedenhancements$planExact(this.whatToCraft, appliedenhancements$exactRequestedAmount)
                            : appliedenhancements$requestedAmount > Integer.MAX_VALUE
                            ? appliedenhancements$planLong(
                                    this.whatToCraft,
                                    appliedenhancements$requestedAmount,
                                    appliedenhancements$calculationStrategy)
                            : NativeCraftingMenuCompat.plan(menu,
                                    this.whatToCraft,
                                    appliedenhancements$requestedAmount,
                                    appliedenhancements$calculationStrategy));
        } finally {
            appliedenhancements$replanningAfterReservationConflict = false;
        }
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
        NetworkHandler.sendToPlayer(
                player,
                new CraftingCalculationProgressPayload(menu.containerId, snapshot));
        if (snapshot.phase().terminal()) {
            appliedenhancements$terminalProgressSentGeneration = snapshot.generation();
        }
    }

    /**
     * AE2 leaves the plan screen open forever when a confirmation menu never
     * receives a planning job, for example when another mod mixes into the plan
     * bookkeeping and fails. Close such a screen instead of showing an endless
     * "calculating" dialog.
     */
    @Inject(method = { "broadcastChanges", "m_38946_" }, at = @At("RETURN"))
    private void appliedenhancements$closeStalledPlanScreen(CallbackInfo callback) {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide()
                || !(menu.getPlayer() instanceof ServerPlayer player)
                || player.containerMenu != menu) {
            return;
        }
        if (this.job != null || this.result != null || menu.getPlan() != null) {
            appliedenhancements$idleTicksWithoutPlanning = 0;
            return;
        }
        if (++appliedenhancements$idleTicksWithoutPlanning
                < APPLIEDENHANCEMENTS_STALLED_PLAN_TICKS) {
            return;
        }
        appliedenhancements$idleTicksWithoutPlanning = 0;
        com.appliedenhancements.runtime.AelisPlanningLog.warn(
                "Crafting plan screen #{} had no planning job for {} ticks; closing it (what={}, amount={})",
                menu.containerId, APPLIEDENHANCEMENTS_STALLED_PLAN_TICKS,
                this.whatToCraft, NativeCraftingMenuCompat.amount(menu));
        player.sendSystemMessage(Component.translatable(
                "message.appliedenhancements.crafting_plan_stalled"));
        menu.goBack();
        if (player.containerMenu == menu) {
            player.closeContainer();
        }
    }

    @Inject(method = { "removed", "m_6877_" }, at = @At("HEAD"))
    private void appliedenhancements$cancelProgressWhenClosed(
            net.minecraft.world.entity.player.Player player,
            CallbackInfo callback) {
        if (!((CraftConfirmMenu) (Object) this).isClientSide()) {
            appliedenhancements$releaseInventoryReservation();
            appliedenhancements$cancelProgress();
        }
    }
}
