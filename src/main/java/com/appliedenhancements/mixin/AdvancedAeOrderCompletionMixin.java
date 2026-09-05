package com.appliedenhancements.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.runtime.DataEnergisticsOrderCompletion;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Quantum counterpart of Data Energistics' native COMPLETE_WITHOUT_OUTPUT handling. */
@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic", remap = false, priority = 1100)
public abstract class AdvancedAeOrderCompletionMixin {
    @Shadow public abstract GenericStack getFinalJobOutput();
    @Shadow public abstract ICraftingLink getLastLink();
    @Shadow public abstract boolean hasJob();
    @Shadow public abstract long getWaitingFor(AEKey key);
    @Shadow public abstract long insert(AEKey key, long amount, Actionable mode);
    @Shadow public abstract void cancel();

    @Unique private static final String APPLIEDENHANCEMENTS_ORDER_TAG = "appliedenhancementsOrderCompletion";
    @Unique private AEKey appliedenhancements$orderKey;
    @Unique private UUID appliedenhancements$orderId;
    @Unique private long appliedenhancements$pendingOrderCompletion;
    @Unique private CraftingLink appliedenhancements$completingOrderLink;

    @Inject(method = "trySubmitJob", at = @At("RETURN"))
    private void appliedenhancements$startOrder(IGrid grid, ICraftingPlan plan,
            IActionSource source, ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (callback.getReturnValue() != null && callback.getReturnValue().successful()) {
            appliedenhancements$prepareOrder();
        }
    }

    @Unique
    private void appliedenhancements$prepareOrder() {
        appliedenhancements$orderKey = null;
        appliedenhancements$orderId = null;
        appliedenhancements$pendingOrderCompletion = 0;
        var output = getFinalJobOutput();
        var link = getLastLink();
        if (link != null && DataEnergisticsOrderCompletion.isVirtualOrder(output)) {
            appliedenhancements$orderKey = output.what();
            appliedenhancements$orderId = link.getCraftingID();
        }
    }

    // This insertion occurs only after a successful provider push. The actual CPU
    // output counter already includes Omni batches and any scaled-pattern multiplier.
    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lappeng/crafting/inv/ListCraftingInventory;insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)V"))
    private void appliedenhancements$recordAcceptedOrderOutput(ListCraftingInventory waiting,
            AEKey key, long amount, Actionable mode, Operation<Void> original) {
        original.call(waiting, key, amount, mode);
        if (mode == Actionable.MODULATE && amount > 0 && key.equals(appliedenhancements$orderKey)) {
            appliedenhancements$pendingOrderCompletion = Math.addExact(
                    appliedenhancements$pendingOrderCompletion, amount);
        }
    }

    @WrapMethod(method = "executeCrafting")
    private int appliedenhancements$completeAcceptedOrders(int maxPatterns,
            CraftingService service, IEnergyService energy, Level level, Operation<Integer> original) {
        appliedenhancements$drainOrderCompletion();
        int executed = original.call(maxPatterns, service, energy, level);
        appliedenhancements$drainOrderCompletion();
        return executed;
    }

    @Unique
    private void appliedenhancements$drainOrderCompletion() {
        if (appliedenhancements$pendingOrderCompletion <= 0 || appliedenhancements$completingOrderLink != null) return;
        var link = getLastLink();
        if (!hasJob() || link == null || !link.getCraftingID().equals(appliedenhancements$orderId)) return;
        var key = appliedenhancements$orderKey;
        long amount = Math.min(appliedenhancements$pendingOrderCompletion, getWaitingFor(key));
        if (amount <= 0) return;
        // Remove the ledger entry first: insert may finish and clear this CPU's job.
        appliedenhancements$pendingOrderCompletion -= amount;
        appliedenhancements$completingOrderLink = (CraftingLink) link;
        try {
            long completed = insert(key, amount, Actionable.MODULATE);
            if (completed != amount) {
                AppliedEnhancements.LOGGER.error("Quantum CPU could not settle virtual order completion {} x{}", key, amount);
                if (hasJob() && getLastLink() == link) cancel();
            }
        } finally {
            appliedenhancements$completingOrderLink = null;
        }
    }

    @WrapOperation(method = "insert", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/CraftingLink;insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J"))
    private long appliedenhancements$skipVirtualPackageDelivery(CraftingLink link, AEKey key,
            long amount, Actionable mode, Operation<Long> original) {
        return link == appliedenhancements$completingOrderLink && key.equals(appliedenhancements$orderKey)
                ? amount : original.call(link, key, amount, mode);
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"))
    private void appliedenhancements$saveOrderCompletion(CompoundTag tag, HolderLookup.Provider registries,
            CallbackInfo callback) {
        if (hasJob() && appliedenhancements$pendingOrderCompletion > 0 && appliedenhancements$orderId != null) {
            var saved = new CompoundTag();
            saved.putUUID("orderId", appliedenhancements$orderId);
            saved.putLong("amount", appliedenhancements$pendingOrderCompletion);
            tag.put(APPLIEDENHANCEMENTS_ORDER_TAG, saved);
        } else {
            tag.remove(APPLIEDENHANCEMENTS_ORDER_TAG);
        }
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void appliedenhancements$loadOrderCompletion(CompoundTag tag, HolderLookup.Provider registries,
            CallbackInfo callback) {
        appliedenhancements$prepareOrder();
        if (appliedenhancements$orderId == null || !tag.contains(APPLIEDENHANCEMENTS_ORDER_TAG)) return;
        var saved = tag.getCompound(APPLIEDENHANCEMENTS_ORDER_TAG);
        long amount = saved.getLong("amount");
        if (saved.hasUUID("orderId") && saved.getUUID("orderId").equals(appliedenhancements$orderId)
                && amount > 0 && amount <= getWaitingFor(appliedenhancements$orderKey)) {
            appliedenhancements$pendingOrderCompletion = amount;
        } else {
            AppliedEnhancements.LOGGER.warn("Ignoring mismatched quantum CPU virtual order completion state");
        }
    }

    @Inject(method = "finishJob", at = @At("TAIL"))
    private void appliedenhancements$clearOrderCompletion(boolean success, CallbackInfo callback) {
        appliedenhancements$orderKey = null;
        appliedenhancements$orderId = null;
        appliedenhancements$pendingOrderCompletion = 0;
    }
}
