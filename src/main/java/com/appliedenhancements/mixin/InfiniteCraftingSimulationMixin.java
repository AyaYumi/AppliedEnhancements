package com.appliedenhancements.mixin;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.storage.InfinitePlanningInventory;
import com.appliedenhancements.storage.InfiniteStorageSupport;
import com.github.appliedenhancements.crafting.aelis.AelisBigIntegerMath;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingSimulationState.class, remap = false)
public abstract class InfiniteCraftingSimulationMixin implements InfinitePlanningInventory {
    @Shadow @Final private KeyCounter requiredExtract;
    @Unique private final Set<AEKey> appliedenhancements$infinite = new HashSet<>();
    @Unique private final Set<AEKey> appliedenhancements$ignoredInfinite = new HashSet<>();
    @Unique private final Map<AEKey, BigInteger> appliedenhancements$infiniteUsed = new HashMap<>();

    @Override public Set<AEKey> appliedenhancements$infiniteKeys() { return appliedenhancements$infinite; }
    @Override public Set<AEKey> appliedenhancements$ignoredInfiniteKeys() { return appliedenhancements$ignoredInfinite; }
    @Override public Map<AEKey, BigInteger> appliedenhancements$infiniteUsedAmounts() {
        return Map.copyOf(appliedenhancements$infiniteUsed);
    }
    @Override public void appliedenhancements$useInfinite(AEKey key, BigInteger amount) {
        if (amount.signum() <= 0) return;
        BigInteger used = appliedenhancements$infiniteUsed.merge(key, amount, BigInteger::add);
        requiredExtract.set(key, AelisBigIntegerMath.saturatingLong(used));
        if (used.bitLength() > 63) {
            // Native CPUs still stage input quantities as long. Correct preview, no false shortage,
            // but do not submit a truncated input budget as an executable job.
            ((com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier) this)
                    .appliedenhancements$setPreviewOnly(true);
        }
    }

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$extractInfinite(AEKey key, long amount, Actionable mode,
            CallbackInfoReturnable<Long> callback) {
        if (amount > 0 && InfiniteStorageSupport.isInfinite((CraftingSimulationState) (Object) this, key)) {
            if (mode == Actionable.MODULATE) appliedenhancements$useInfinite(key, BigInteger.valueOf(amount));
            callback.setReturnValue(amount);
        }
    }

    @Inject(method = "insert", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$ignoreInfiniteReturns(AEKey key, long amount, Actionable mode,
            CallbackInfo callback) {
        if (InfiniteStorageSupport.isInfinite((CraftingSimulationState) (Object) this, key)) callback.cancel();
    }

    @Inject(method = "ignore", at = @At("RETURN"))
    private void appliedenhancements$ignoreInfiniteTarget(AEKey key, CallbackInfo callback) {
        appliedenhancements$ignoredInfinite.add(key);
    }

    @WrapMethod(method = "applyDiff")
    private void appliedenhancements$mergeInfiniteRequirements(CraftingSimulationState parent,
            Operation<Void> original) {
        // Infinite stock has no finite cache delta. Transfer its exact consumption separately,
        // so AE2's long addition never overflows and abandoned child branches remain isolated.
        Map<AEKey, Long> saved = new HashMap<>();
        appliedenhancements$infiniteUsed.forEach((key, amount) -> {
            saved.put(key, requiredExtract.get(key));
            requiredExtract.set(key, 0);
        });
        try {
            original.call(parent);
            appliedenhancements$infiniteUsed.forEach(
                    ((InfinitePlanningInventory) parent)::appliedenhancements$useInfinite);
        } finally {
            saved.forEach(requiredExtract::set);
        }
    }
}
