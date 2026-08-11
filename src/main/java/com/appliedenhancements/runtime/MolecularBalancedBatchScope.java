package com.appliedenhancements.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import appeng.api.stacks.KeyCounter;

import com.appliedenhancements.api.MolecularBalancedBatchProvider;
import org.jetbrains.annotations.ApiStatus;

/**
 * Tracks providers opened during one crafting-CPU scheduling pass.
 */
@ApiStatus.Internal
public final class MolecularBalancedBatchScope implements AutoCloseable {
    private final IdentityHashMap<MolecularBalancedBatchProvider, Boolean> opened =
            new IdentityHashMap<>();
    private final List<MolecularBalancedBatchProvider> closeOrder = new ArrayList<>();
    private boolean closed;

    public MolecularBalancedBatchScope() {
    }

    public void beginIfNeeded(Object provider, KeyCounter[] firstInputs) {
        if (closed) {
            throw new IllegalStateException("Batch scope is already closed");
        }
        if (!(provider instanceof MolecularBalancedBatchProvider batchProvider)
                || opened.containsKey(batchProvider)) {
            return;
        }

        batchProvider.appliedenhancements$beginAdaptiveBatch(snapshot(firstInputs));
        opened.put(batchProvider, Boolean.TRUE);
        closeOrder.add(batchProvider);
    }

    int openedProviderCount() {
        return closeOrder.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        Throwable firstFailure = null;
        for (int index = closeOrder.size() - 1; index >= 0; index--) {
            try {
                closeOrder.get(index).appliedenhancements$endBalancedBatch();
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (failure != firstFailure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        closeOrder.clear();
        opened.clear();

        if (firstFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
        if (firstFailure != null) {
            throw new RuntimeException(firstFailure);
        }
    }

    private static KeyCounter[] snapshot(KeyCounter[] inputs) {
        if (inputs == null) {
            return null;
        }
        KeyCounter[] result = new KeyCounter[inputs.length];
        for (int index = 0; index < inputs.length; index++) {
            KeyCounter input = inputs[index];
            if (input != null) {
                var copy = new KeyCounter();
                copy.addAll(input);
                result[index] = copy;
            }
        }
        return result;
    }
}
