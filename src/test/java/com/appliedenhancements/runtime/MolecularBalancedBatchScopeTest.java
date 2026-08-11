package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.KeyCounter;

import com.appliedenhancements.api.MolecularBalancedBatchProvider;

class MolecularBalancedBatchScopeTest {
    @Test
    void bracketsEachProviderOnceAndClosesInReverseOrder() {
        var events = new ArrayList<String>();
        var first = new RecordingProvider("first", events);
        var second = new RecordingProvider("second", events);
        var inputs = new KeyCounter[] { new KeyCounter(), null };

        try (var scope = new MolecularBalancedBatchScope()) {
            scope.beginIfNeeded(first, inputs);
            scope.beginIfNeeded(first, new KeyCounter[] { new KeyCounter() });
            scope.beginIfNeeded(second, inputs);
            scope.beginIfNeeded(new Object(), inputs);

            assertEquals(2, scope.openedProviderCount());
            assertNotSame(inputs, first.firstInputs);
            assertNotSame(inputs[0], first.firstInputs[0]);
        }

        assertEquals(List.of("begin:first", "begin:second", "end:second", "end:first"), events);
    }

    @Test
    void beginFailureIsNotPairedWithEnd() {
        var provider = new ThrowingBeginProvider();
        var scope = new MolecularBalancedBatchScope();

        assertThrows(IllegalStateException.class,
                () -> scope.beginIfNeeded(provider, new KeyCounter[0]));
        scope.close();
        assertEquals(0, provider.endCalls);
    }

    @Test
    void closeAttemptsEveryProviderAndSuppressesLaterFailures() {
        var first = new ThrowingEndProvider("first");
        var second = new ThrowingEndProvider("second");
        var scope = new MolecularBalancedBatchScope();
        scope.beginIfNeeded(first, new KeyCounter[0]);
        scope.beginIfNeeded(second, new KeyCounter[0]);

        var failure = assertThrows(IllegalStateException.class, scope::close);
        assertEquals("second", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("first", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void sharedEndFailureDoesNotPreventRemainingProvidersFromClosing() {
        var events = new ArrayList<String>();
        var sharedFailure = new IllegalStateException("shared");
        var first = new SharedFailureProvider("first", events, sharedFailure);
        var second = new SharedFailureProvider("second", events, sharedFailure);
        var third = new SharedFailureProvider("third", events, sharedFailure);
        var scope = new MolecularBalancedBatchScope();
        scope.beginIfNeeded(first, new KeyCounter[0]);
        scope.beginIfNeeded(second, new KeyCounter[0]);
        scope.beginIfNeeded(third, new KeyCounter[0]);

        var failure = assertThrows(IllegalStateException.class, scope::close);

        assertSame(sharedFailure, failure);
        assertEquals(List.of("end:third", "end:second", "end:first"), events);
        assertEquals(0, failure.getSuppressed().length);
    }

    private static final class RecordingProvider implements MolecularBalancedBatchProvider {
        private final String name;
        private final List<String> events;
        private KeyCounter[] firstInputs;

        private RecordingProvider(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void appliedenhancements$beginBalancedBatch(KeyCounter[] firstInputs) {
            this.firstInputs = firstInputs;
            events.add("begin:" + name);
        }

        @Override
        public void appliedenhancements$endBalancedBatch() {
            events.add("end:" + name);
        }
    }

    private static final class ThrowingBeginProvider implements MolecularBalancedBatchProvider {
        private int endCalls;

        @Override
        public void appliedenhancements$beginBalancedBatch(KeyCounter[] firstInputs) {
            throw new IllegalStateException("begin");
        }

        @Override
        public void appliedenhancements$endBalancedBatch() {
            endCalls++;
        }
    }

    private static final class ThrowingEndProvider implements MolecularBalancedBatchProvider {
        private final String message;

        private ThrowingEndProvider(String message) {
            this.message = message;
        }

        @Override
        public void appliedenhancements$beginBalancedBatch(KeyCounter[] firstInputs) {
        }

        @Override
        public void appliedenhancements$endBalancedBatch() {
            throw new IllegalStateException(message);
        }
    }

    private static final class SharedFailureProvider implements MolecularBalancedBatchProvider {
        private final String name;
        private final List<String> events;
        private final RuntimeException failure;

        private SharedFailureProvider(
                String name, List<String> events, RuntimeException failure) {
            this.name = name;
            this.events = events;
            this.failure = failure;
        }

        @Override
        public void appliedenhancements$beginBalancedBatch(KeyCounter[] firstInputs) {
        }

        @Override
        public void appliedenhancements$endBalancedBatch() {
            events.add("end:" + name);
            throw failure;
        }
    }
}
