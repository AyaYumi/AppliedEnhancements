package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderTarget;
import com.appliedenhancements.runtime.AelisPlanningLog;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = PatternProviderLogic.class, remap = false)
public abstract class ProcessingPatternPushMixin {
    @WrapOperation(method = "pushPattern", at = @At(value = "INVOKE",
            target = "Lappeng/helpers/patternprovider/PatternProviderLogic;adapterAcceptsAll(Lappeng/helpers/patternprovider/PatternProviderTarget;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean appliedenhancements$prepareProcessingInputs(PatternProviderLogic provider,
            PatternProviderTarget target, KeyCounter[] inputs, Operation<Boolean> original,
            @Local(argsOnly = true) IPatternDetails details,
            @Share("processingInputs") LocalRef<List<GenericStack>> prepared) {
        if (!original.call(provider, target, inputs)) {
            return false;
        }
        if (details.getClass() != AEProcessingPattern.class) {
            return true;
        }
        var pattern = (AEProcessingPattern) details;
        if (pattern.getSparseInputs().size() == pattern.getInputs().length) {
            return true;
        }

        // Resolve the complete sparse order before the target receives any material.
        // Keep the CPU's holder intact so a rejection can use AE2's normal refund path.
        try {
            var ordered = new ArrayList<GenericStack>();
            var remaining = new KeyCounter();
            var snapshot = new KeyCounter[inputs.length];
            for (int index = 0; index < inputs.length; index++) {
                var copy = snapshot[index] = new KeyCounter();
                copy.addAll(inputs[index]);
                for (var entry : copy) {
                    var key = entry.getKey();
                    long amount = entry.getLongValue();
                    if (amount < 0) {
                        throw new IllegalArgumentException("Negative reserved processing input");
                    }
                    remaining.set(key, Math.addExact(remaining.get(key), amount));
                }
            }
            pattern.pushInputsToExternalInventory(snapshot, (key, amount) -> {
                if (amount <= 0 || remaining.get(key) < amount) {
                    throw new IllegalArgumentException("Invalid ordered processing input: " + key);
                }
                remaining.remove(key, amount);
                ordered.add(new GenericStack(key, amount));
            });
            remaining.removeZeros();
            if (!remaining.isEmpty()) {
                throw new IllegalArgumentException("Ordered processing inputs did not use all reserved materials");
            }
            prepared.set(ordered);
            return true;
        } catch (RuntimeException invalidInputs) {
            AelisPlanningLog.warn("Rejected external processing pattern for {}: {}",
                    details.getPrimaryOutput(), invalidInputs.getMessage());
            return false;
        }
    }

    @WrapOperation(method = "pushPattern", at = @At(value = "INVOKE",
            target = "Lappeng/api/crafting/IPatternDetails;pushInputsToExternalInventory([Lappeng/api/stacks/KeyCounter;Lappeng/api/crafting/IPatternDetails$PatternInputSink;)V"))
    private void appliedenhancements$sendProcessingInputs(IPatternDetails details,
            KeyCounter[] inputs, IPatternDetails.PatternInputSink sink, Operation<Void> original,
            @Share("processingInputs") LocalRef<List<GenericStack>> prepared) {
        var ordered = prepared.get();
        if (ordered == null) {
            original.call(details, inputs, sink);
            return;
        }
        for (var entry : ordered) {
            sink.pushInput(entry.what(), entry.amount());
        }
    }
}
