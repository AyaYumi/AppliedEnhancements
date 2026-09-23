package com.appliedenhancements.mixin;

import appeng.menu.me.crafting.CraftingStatusEntry;
import com.appliedenhancements.ae2.ExactCraftingStatusEntry;
import com.appliedenhancements.runtime.ExactCraftingStatus;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingStatusEntry.class, remap = false)
public abstract class CraftingStatusEntryExactMixin implements ExactCraftingStatusEntry {
    @Unique private com.appliedenhancements.api.AelisCraftingBatch appliedenhancements$lastBatch;
    @Override public com.appliedenhancements.api.AelisCraftingBatch appliedenhancements$getLastBatch() { return appliedenhancements$lastBatch; }
    @Override public void appliedenhancements$setLastBatch(com.appliedenhancements.api.AelisCraftingBatch batch) { appliedenhancements$lastBatch = batch; }
    @Unique private BigInteger appliedenhancements$pending;
    @Unique private BigInteger appliedenhancements$active;
    @Unique private BigInteger appliedenhancements$stored;
    @Unique private BigInteger appliedenhancements$completed;
    @Override public BigInteger appliedenhancements$getCompleted() { return appliedenhancements$completed; }
    @Override public void appliedenhancements$setCompleted(BigInteger amount) {
        if (amount != null && amount.signum() < 0) throw new IllegalArgumentException("Negative completed amount");
        appliedenhancements$completed = amount;
    }
    @Inject(method = "isDeleted", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$keepCompleted(CallbackInfoReturnable<Boolean> ci) {
        if (appliedenhancements$lastBatch != null) ci.setReturnValue(false);
    }
    @Override public BigInteger appliedenhancements$getStored() { return appliedenhancements$stored; }
    @Override public void appliedenhancements$setStored(BigInteger amount) {
        if (amount != null && amount.signum() < 0) throw new IllegalArgumentException("Negative stored amount");
        appliedenhancements$stored = amount;
    }
    @Override public BigInteger appliedenhancements$getActive() { return appliedenhancements$active; }
    @Override public void appliedenhancements$setActive(BigInteger amount) {
        if (amount != null && amount.signum() < 0) throw new IllegalArgumentException("Negative active amount");
        appliedenhancements$active = amount;
    }
    @Override public BigInteger appliedenhancements$getPending() { return appliedenhancements$pending; }
    @Override public void appliedenhancements$setPending(BigInteger amount) {
        if (amount != null && amount.signum() < 0) throw new IllegalArgumentException("Negative pending amount");
        appliedenhancements$pending = amount;
    }
    @Inject(method = "compareTo(Lappeng/menu/me/crafting/CraftingStatusEntry;)I", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$sortExact(CraftingStatusEntry other, CallbackInfoReturnable<Integer> ci) {
        var self = (CraftingStatusEntry) (Object) this;
        var left = ExactCraftingStatus.pending(self).add(ExactCraftingStatus.active(self));
        var right = ExactCraftingStatus.pending(other).add(ExactCraftingStatus.active(other));
        int order = right.compareTo(left);
        ci.setReturnValue(order != 0 ? order : ExactCraftingStatus.stored(other).compareTo(ExactCraftingStatus.stored(self)));
    }
}
