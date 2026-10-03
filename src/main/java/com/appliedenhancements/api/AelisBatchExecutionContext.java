package com.appliedenhancements.api;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import com.appliedenhancements.runtime.AelisCycleDispatch;
import com.appliedenhancements.runtime.AelisCycleDispatchScope;
import java.util.Objects;

/**
 * One CPU extraction attempt, shared by the first recipe, batch expansion and rollback.
 * Acquire inside the CPU's scheduling scope; do not reuse for another pattern/attempt.
 */
public final class AelisBatchExecutionContext {
    private static final ThreadLocal<Dispatch> DISPATCH = new ThreadLocal<>();
    private final AelisCycleRuntimeController runtime;
    private final AEKey pattern;
    private final ICraftingInventory inventory;
    private final long maximumCrafts;

    private AelisBatchExecutionContext(AelisCycleRuntimeController runtime,
            AEKey pattern, ICraftingInventory source) {
        this.runtime = runtime;
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        inventory = AelisCycleDispatch.inventory(runtime, pattern, Objects.requireNonNull(source, "source"));
        maximumCrafts = inventory == null ? 0 : runtime != null && runtime.currentStep()
                .filter(step -> step.patternDefinition().equals(pattern)).isPresent()
                        ? runtime.remainingCrafts() : Long.MAX_VALUE;
    }

    public static AelisBatchExecutionContext acquire(AEKey pattern, ICraftingInventory source) {
        return new AelisBatchExecutionContext(AelisCycleDispatchScope.currentRuntime(), pattern, source);
    }

    /** Explicit runtime entry point for independent CPUs that do not use the native scheduling scope. */
    public static AelisBatchExecutionContext acquire(AelisCycleRuntimeController runtime,
            AEKey pattern, ICraftingInventory source) {
        return new AelisBatchExecutionContext(runtime, pattern, source);
    }

    /** Null means this pattern cannot currently execute. */
    public ICraftingInventory inventory() { return inventory; }
    public long maximumCrafts() { return maximumCrafts; }

    /** Unbounded inventory shortcuts must not bypass active cycle/seed protection. */
    public boolean hasCycleProtection() { return runtime != null; }

    /**
     * Validate and register the actual batch before handing materials to a provider,
     * including providers that commit through an API instead of pushPattern.
     * Call accepted() only once material ownership has transferred. Closing a rejected
     * dispatch restores progress and the pending-output ledger.
     */
    public Dispatch beginDispatch(KeyCounter[] inputs, long crafts) {
        if (crafts <= 0 || crafts > maximumCrafts) {
            throw new IllegalStateException("Batch exceeds the current cycle execution step");
        }
        return begin(runtime, pattern, inputs, crafts);
    }

    /** Native provider hooks share an enclosing batch transaction, avoiding double advancement. */
    public static Dispatch beginProviderDispatch(AelisCycleRuntimeController runtime,
            AEKey pattern, KeyCounter[] inputs) {
        if (matches(runtime, pattern, inputs)) return new Dispatch(DISPATCH.get());
        long crafts = AelisCycleDispatch.dispatchedProviderPush(runtime, pattern, inputs);
        return begin(runtime, pattern, inputs, crafts == 0 ? 1 : crafts);
    }

    private static boolean matches(AelisCycleRuntimeController runtime, AEKey pattern, KeyCounter[] inputs) {
        var current = DISPATCH.get();
        return current != null && current.runtime == runtime
                && current.pattern.equals(pattern) && current.inputs == inputs;
    }

    private static Dispatch begin(AelisCycleRuntimeController runtime, AEKey pattern,
            KeyCounter[] inputs, long crafts) {
        Objects.requireNonNull(inputs, "inputs");
        for (var input : inputs) Objects.requireNonNull(input, "input holder");
        if (matches(runtime, pattern, inputs)) return new Dispatch(DISPATCH.get());
        if (runtime != null) {
            if (!runtime.canDispatch(pattern, java.util.Set.of())) {
                throw new IllegalStateException("Pattern is not dispatchable in the current cycle phase");
            }
            if (runtime.currentStep().filter(step -> step.patternDefinition().equals(pattern)).isPresent()) {
                var required = runtime.currentStep().orElseThrow().inputsPerCraft();
                if (crafts > runtime.remainingCrafts() || crafts <= 0
                        || !required.isEmpty() && AelisCycleDispatch.dispatchedCrafts(runtime, pattern, inputs) != crafts) {
                    throw new IllegalStateException("Batch inputs do not match the declared cycle craft count");
                }
                return new Dispatch(runtime, pattern, inputs, crafts);
            }
        }
        return new Dispatch();
    }

    /** API committers call this as soon as their delivery reports durable ownership. */
    public static void acceptCurrentDispatch() {
        var current = DISPATCH.get();
        if (current != null) current.accepted();
    }

    public static final class Dispatch implements AutoCloseable {
        private final AelisCycleRuntimeController runtime;
        private final AEKey pattern;
        private final KeyCounter[] inputs;
        private final AelisCycleRuntimeController.State before;
        private final Dispatch previous;
        private final Dispatch owner;
        private boolean accepted;
        private boolean closed;

        private Dispatch() {
            this(null);
        }

        private Dispatch(Dispatch owner) {
            runtime = null; pattern = null; inputs = null; before = null; previous = null;
            this.owner = owner;
        }

        private Dispatch(AelisCycleRuntimeController runtime, AEKey pattern, KeyCounter[] inputs, long crafts) {
            this.runtime = runtime;
            this.pattern = pattern;
            this.inputs = inputs;
            owner = null;
            before = runtime.snapshot();
            previous = DISPATCH.get();
            runtime.patternDispatched(pattern, crafts);
            DISPATCH.set(this);
        }

        public void accepted() {
            accepted = true;
            if (owner != null) owner.accepted();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (runtime == null) return;
            try {
                if (!accepted) runtime.restore(before);
            } finally {
                if (previous == null) DISPATCH.remove();
                else DISPATCH.set(previous);
            }
        }
    }
}
