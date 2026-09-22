package com.appliedenhancements.mixin;

import appeng.api.stacks.AEKey;
import appeng.crafting.inv.CraftingSimulationState;
import com.appliedenhancements.runtime.CraftingByteEstimate;
import com.appliedenhancements.runtime.ExactCraftingBytes;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import com.github.appliedenhancements.integration.ae2.AelisCraftingBytesTracker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingSimulationState.class, remap = false)
public abstract class CraftingSimulationStateBytesMixin implements AelisCraftingBytesTracker {
    @Unique private CraftingByteEstimate appliedenhancements$exactBytes = CraftingByteEstimate.ZERO;
    @Unique private boolean appliedenhancements$projectingBytes;

    @Override public CraftingByteEstimate appliedenhancements$getExactByteEstimate() {
        return appliedenhancements$exactBytes;
    }

    @Override public void appliedenhancements$addExactBytes(CraftingByteEstimate bytes) {
        appliedenhancements$exactBytes = appliedenhancements$exactBytes.add(bytes);
        appliedenhancements$projectingBytes = true;
        try {
            ((CraftingSimulationState) (Object) this).addBytes(bytes.projection());
        } finally {
            appliedenhancements$projectingBytes = false;
        }
    }

    /** Overrides the inherited interface default, preserving integer operands and fluid fractions. */
    public void addStackBytes(AEKey key, long amount, long multiplier) {
        if (!CraftingPlannerIntervention.enabledFor(this)) {
            ((CraftingSimulationState) (Object) this).addBytes(
                    (double) amount * (double) multiplier
                            / key.getType().getAmountPerByte() * 8.0);
            return;
        }
        ExactCraftingBytes.addStackBytes((CraftingSimulationState) (Object) this, key,
                BigInteger.valueOf(amount).multiply(BigInteger.valueOf(multiplier)));
    }

    @Inject(method = "addBytes", at = @At("HEAD"))
    private void appliedenhancements$trackNativeBytes(double bytes, CallbackInfo callback) {
        if (CraftingPlannerIntervention.enabledFor(this) && !appliedenhancements$projectingBytes) {
            appliedenhancements$exactBytes = appliedenhancements$exactBytes.add(CraftingByteEstimate.fromDouble(bytes));
        }
    }

    @WrapOperation(method = "applyDiff", at = @At(value = "INVOKE",
            target = "Lappeng/crafting/inv/CraftingSimulationState;addBytes(D)V"))
    private void appliedenhancements$mergeExactBytes(CraftingSimulationState parent, double bytes,
            Operation<Void> original) {
        if (!CraftingPlannerIntervention.enabledFor(this)) {
            original.call(parent, bytes);
            return;
        }
        ((AelisCraftingBytesTracker) parent).appliedenhancements$addExactBytes(appliedenhancements$exactBytes);
    }
}
