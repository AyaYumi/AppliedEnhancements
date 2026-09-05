package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import com.appliedenhancements.api.AelisCycleRuntimeController;
import java.util.ArrayList;
import java.util.function.Supplier;

/** Limits optional provider batching to the active cycle in a CPU scheduling pass. */
public final class AelisCycleDispatchScope implements AutoCloseable {
    private static final ThreadLocal<Supplier<AelisCycleRuntimeController>> CURRENT = new ThreadLocal<>();
    private final Supplier<AelisCycleRuntimeController> previous;

    private AelisCycleDispatchScope(Supplier<AelisCycleRuntimeController> runtime) {
        previous = CURRENT.get();
        CURRENT.set(runtime);
    }

    public static AelisCycleDispatchScope open(Supplier<AelisCycleRuntimeController> runtime) {
        return new AelisCycleDispatchScope(runtime);
    }

    public static AelisCycleDispatchScope open(AelisCycleRuntimeController runtime) {
        return open(() -> runtime);
    }

    public static Iterable<ICraftingProvider> providers(
            IPatternDetails pattern, Iterable<ICraftingProvider> providers) {
        var current = CURRENT.get();
        var runtime = current == null ? null : current.get();
        if (runtime == null || runtime.currentStep()
                .filter(step -> step.patternDefinition().equals(pattern.getDefinition())).isEmpty()) {
            return providers;
        }
        var wrapped = new ArrayList<ICraftingProvider>();
        for (var provider : providers) wrapped.add(AelisSmartCycleBatchProvider.wrap(provider, pattern));
        return wrapped;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
