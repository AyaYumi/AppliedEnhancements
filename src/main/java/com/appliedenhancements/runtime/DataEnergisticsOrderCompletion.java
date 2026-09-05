package com.appliedenhancements.runtime;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.appliedenhancements.AppliedEnhancements;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/** Uses Data Energistics' own marker validation without requiring that mod at runtime. */
public final class DataEnergisticsOrderCompletion {
    private static final ClassValue<Optional<Field>> NATIVE_LEDGER = new ClassValue<>() {
        @Override protected Optional<Field> computeValue(Class<?> type) {
            try {
                var field = type.getDeclaredField("dataEnergistics$pendingNoOutputCompletions");
                field.setAccessible(true);
                return Optional.of(field);
            } catch (ReflectiveOperationException | RuntimeException absent) {
                return Optional.empty();
            }
        }
    };

    private DataEnergisticsOrderCompletion() {}

    /** Captures only the native DE ledger that will be updated by this accepted push. */
    public static NativeSnapshot beforeNativePush(Object logic, GenericStack finalOutput, KeyCounter outputs) {
        if (finalOutput == null || outputs.get(finalOutput.what()) <= 0 || !isVirtualOrder(finalOutput)) return null;
        var field = NATIVE_LEDGER.get(logic.getClass()).orElse(null);
        if (field == null) return null;
        try {
            var ledger = (ListCraftingInventory) field.get(logic);
            var key = finalOutput.what();
            return new NativeSnapshot(ledger, key, ledger.list.get(key), outputs.get(key));
        } catch (IllegalAccessException inaccessible) {
            AppliedEnhancements.LOGGER.warn("Could not inspect native Data Energistics order completion", inaccessible);
            return null;
        }
    }

    public record NativeSnapshot(ListCraftingInventory ledger, AEKey key, long before, long expected) {
        public void reconcileAcceptedPush() {
            long recorded = ledger.list.get(key) - before;
            if (recorded > 0 && recorded != expected) {
                // DE records virtualCompletions(1). Keep prior queued work and replace
                // only this push's addition with the CPU's actual (possibly batched) output.
                ledger.list.set(key, Math.addExact(before, expected));
            }
        }
    }

    public static boolean isVirtualOrder(GenericStack output) {
        if (output == null || !(output.what() instanceof AEItemKey)
                || !"data_energistics:order_package".equals(output.what().getId().toString())) return false;
        var resolver = Adapter.METHOD;
        if (resolver == null) return false;
        try {
            return (boolean) resolver.invoke(null, output);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            AppliedEnhancements.LOGGER.warn("Could not resolve Data Energistics order completion", unavailable);
            return false;
        }
    }

    private static final class Adapter {
        private static final Method METHOD = resolve();

        private static Method resolve() {
            try {
                return Class.forName("com.fish_dan_.data_energistics.common.crafting.virtual.VirtualCraftingOutputAdapters")
                        .getMethod("hasNoOutputCompletion", GenericStack.class);
            } catch (ReflectiveOperationException | LinkageError absent) {
                return null;
            }
        }
    }
}
