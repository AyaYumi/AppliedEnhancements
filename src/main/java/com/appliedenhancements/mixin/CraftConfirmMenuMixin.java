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
import appeng.me.helpers.PlayerSource;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.appliedenhancements.api.AelisCraftingRequest;
import com.appliedenhancements.api.AelisExactRequest;
import com.appliedenhancements.runtime.CraftingSubmissionScope;
import com.appliedenhancements.Config;
import com.appliedenhancements.runtime.CraftingProgressSnapshotOrder;
import com.appliedenhancements.runtime.CraftingProgressTaskBinding;
import com.appliedenhancements.runtime.ManualCraftingInventoryLock;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.network.CraftingCalculationPathPayload;
import com.github.appliedenhancements.network.CraftingCalculationProgressPayload;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
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
import java.math.BigInteger;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuMixin implements CraftingCalculationProgressMenuBridge,
        AelisCalculationPathMenuBridge,
        com.appliedenhancements.ae2.ForceCraftingMenuBridge,
        com.appliedenhancements.ae2.AelisPlanGraphMenuBridge {
    @Unique private com.appliedenhancements.plan.AelisPlanGraph appliedenhancements$planGraph;
    @Unique private com.appliedenhancements.plan.AelisPlanGraph appliedenhancements$cachedGraph;
    @Unique private ICraftingPlan appliedenhancements$graphSource;
    @Unique private appeng.menu.me.crafting.CraftingPlanSummary appliedenhancements$graphBinding;
    @Unique private int appliedenhancements$graphRequest;

    @Override
    public com.appliedenhancements.plan.AelisPlanGraph appliedenhancements$getPlanGraph() {
        return appliedenhancements$graphBinding == this.plan ? appliedenhancements$planGraph : null;
    }

    @Override
    public void appliedenhancements$requestPlanGraph() {
        var menu = (CraftConfirmMenu) (Object) this;
        if (!menu.isClientSide() || this.plan == null) return;
        appliedenhancements$graphBinding = this.plan;
        appliedenhancements$planGraph = null;
        appliedenhancements$graphRequest = appliedenhancements$graphRequest == Integer.MAX_VALUE
                ? 1 : appliedenhancements$graphRequest + 1;
        PacketDistributor.sendToServer(new com.appliedenhancements.network.AelisPlanGraphRequest(
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
        if (appliedenhancements$graphSource != this.result) {
            appliedenhancements$graphSource = this.result;
            try {
                appliedenhancements$cachedGraph = com.appliedenhancements.runtime.AelisPlanGraphBuilder.build(this.result);
                // Preflight optional component-rich keys before scheduling a bounded network packet.
                var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), player.registryAccess(), player.connection.getConnectionType());
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
        PacketDistributor.sendToPlayer(player, new com.appliedenhancements.network.AelisPlanGraphPayload(
                menu.containerId, request, appliedenhancements$cachedGraph));
    }
    @Unique
    private static final AtomicLong appliedenhancements$nextProgressGeneration = new AtomicLong();

    @Shadow
    private ICraftingPlan result;

    @Shadow
    private appeng.menu.me.crafting.CraftingPlanSummary plan;

    @Unique private AelisCraftingRequest appliedenhancements$submittedQuantity;

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
    private ManualCraftingInventoryLock.Reservation appliedenhancements$inventoryReservation;

    @Unique
    private ICraftingPlan appliedenhancements$reservedPlan;

    @Unique
    private int appliedenhancements$reservationConflictReplans;

    @Unique
    private boolean appliedenhancements$replanningAfterReservationConflict;

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
    private CraftingCalculationProgressHandle appliedenhancements$startProgressTask(
            boolean progressDisplayEnabled) {
        CraftingCalculationProgressHandle progress = null;
        if (progressDisplayEnabled && Config.ENABLE_AUTOMATIC_AELIS_PLANNER.get()) {
            long generation = appliedenhancements$nextProgressGeneration.incrementAndGet();
            if (generation <= 0) {
                appliedenhancements$nextProgressGeneration.set(1);
                generation = 1;
            }
            progress = new CraftingCalculationProgressHandle(generation);
        }
        appliedenhancements$progressBinding.begin(progress);
        appliedenhancements$progressRevision = 0;
        appliedenhancements$lastProgressSyncTick = Long.MIN_VALUE;
        appliedenhancements$terminalProgressSentGeneration = -1;
        appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
        return progress;
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
        if (this.plan != null) {
            this.plan = null;
        }
    }


    @Inject(method = "planJob", at = @At("HEAD"))
    private void appliedenhancements$cancelProgressBeforePlan(
            AEKey what, int amount, CalculationStrategy strategy,
            CallbackInfoReturnable<Boolean> callback) {
        if (!((CraftConfirmMenu) (Object) this).isClientSide()) {
            var submitted = CraftingSubmissionScope.request();
            appliedenhancements$submittedQuantity = submitted != null && submitted.output().equals(what)
                    && submitted.amount().min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact() == amount
                    ? new AelisCraftingRequest(what, submitted.amount(), strategy) : null;
            if (!appliedenhancements$replanningAfterReservationConflict) {
                appliedenhancements$reservationConflictReplans = 0;
            }
            appliedenhancements$calculationPath = AelisCalculationPath.AE2_NATIVE;
            appliedenhancements$cyclicCraftAmounts = Map.of();
            appliedenhancements$bigIntegerCyclicCraftAmounts = Map.of();
            appliedenhancements$bigIntegerCraftAmounts = Map.of();
            appliedenhancements$bigIntegerMissingAmounts = Map.of();
            appliedenhancements$releaseInventoryReservation();
            appliedenhancements$cancelProgress();
        }
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
        var progress = appliedenhancements$startProgressTask(
                Config.ENABLE_PROGRESS_DISPLAY.get());
        long submittedAmount = appliedenhancements$submittedQuantity == null ? amount
                : new AelisExactRequest(appliedenhancements$submittedQuantity.amount()).projection();
        try (var quantity = CraftingSubmissionScope.withRequest(appliedenhancements$submittedQuantity);
                var tracking = CraftingSubmissionScope.withProgress(progress)) {
            return original.call(craftingService, level, requester, what, submittedAmount, strategy);
        }
    }

    @WrapMethod(method = {"replan", "goBack"})
    private void appliedenhancements$keepSubmittedQuantity(Operation<Void> original) {
        try (var quantity = CraftingSubmissionScope.withRequest(appliedenhancements$submittedQuantity)) {
            original.call();
        }
    }

    @Unique
    private AelisCalculationPath appliedenhancements$resolveCalculationPath(ICraftingPlan plan) {
        AelisCalculationPath carriedPath = plan instanceof AelisCalculationPathCarrier carrier
                ? carrier.molecularmanipulator$getCalculationPath()
                : null;
        return carriedPath == AelisCalculationPath.AELIS
                ? AelisCalculationPath.AELIS
                : AelisCalculationPath.AE2_NATIVE;
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
        PacketDistributor.sendToPlayer(
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
        if (!ManualCraftingInventoryLock.enabled()
                || this.result != null && appliedenhancements$resolveCalculationPath(this.result) != AelisCalculationPath.AELIS) {
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

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "startJob")
    private void appliedenhancements$ownReservationThroughoutStart(Operation<Void> original) {
        var reservation = this.result == appliedenhancements$reservedPlan ? appliedenhancements$inventoryReservation : null;
        if (reservation == null) { original.call(); return; }
        reservation.submit(() -> { original.call(); return null; });
    }

    @Override
    public void appliedenhancements$startForced() {
        var menu = (CraftConfirmMenu) (Object) this;
        if (menu.isClientSide()) {
            PacketDistributor.sendToServer(new com.appliedenhancements.network.ForceCraftingStartPayload(menu.containerId));
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
        this.result = forced;
        if (appliedenhancements$reservedPlan == original) appliedenhancements$reservedPlan = forced;
        try {
            menu.startJob();
        } finally {
            if (this.result == forced) this.result = original;
            if (appliedenhancements$reservedPlan == forced) appliedenhancements$reservedPlan = original;
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
        appliedenhancements$replanningAfterReservationConflict = true;
        try {
            menu.replan();
        } finally {
            appliedenhancements$replanningAfterReservationConflict = false;
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

        var snapshot = progress.snapshot(++appliedenhancements$progressRevision);
        if (snapshot.path() != AelisCalculationPath.AELIS) {
            return;
        }
        appliedenhancements$lastProgressSyncTick = player.serverLevel().getGameTime();
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
