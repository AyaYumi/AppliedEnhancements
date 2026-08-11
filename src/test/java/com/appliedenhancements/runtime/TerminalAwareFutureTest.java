package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class TerminalAwareFutureTest {
    @Test
    void independentFutureCompletionIsObservedByAe2IsDonePoll() throws Exception {
        var delegate = new CompletableFuture<String>();
        var completedValue = new AtomicReference<String>();
        var completions = new AtomicInteger();
        var failures = new AtomicInteger();
        var cancellations = new AtomicInteger();
        var future = new TerminalAwareFuture<>(
                delegate,
                value -> {
                    completedValue.set(value);
                    completions.incrementAndGet();
                },
                failure -> failures.incrementAndGet(),
                cancellations::incrementAndGet);

        assertFalse(future.isDone());
        delegate.complete("external-plan");

        assertTrue(future.isDone());
        assertEquals("external-plan", completedValue.get());
        assertEquals("external-plan", future.get());
        assertEquals(1, completions.get());
        assertEquals(0, failures.get());
        assertEquals(0, cancellations.get());
    }

    @Test
    void independentFutureFailureIsObservedOnce() {
        var delegate = new CompletableFuture<String>();
        var observedFailure = new AtomicReference<Throwable>();
        var failures = new AtomicInteger();
        var future = new TerminalAwareFuture<>(
                delegate,
                value -> {
                },
                failure -> {
                    observedFailure.set(failure);
                    failures.incrementAndGet();
                },
                () -> {
                });
        var failure = new IllegalStateException("planner failed");

        delegate.completeExceptionally(failure);

        assertTrue(future.isDone());
        assertSame(failure, observedFailure.get());
        assertThrows(ExecutionException.class, future::get);
        assertEquals(1, failures.get());
    }

    @Test
    void cancellationFromEitherFutureIsTerminalOnce() {
        var delegate = new CompletableFuture<String>();
        var cancellations = new AtomicInteger();
        var future = new TerminalAwareFuture<>(
                delegate, value -> {
                }, failure -> {
                }, cancellations::incrementAndGet);

        assertTrue(delegate.cancel(true));
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());
        assertEquals(1, cancellations.get());

        var secondDelegate = new CompletableFuture<String>();
        var secondCancellations = new AtomicInteger();
        var second = new TerminalAwareFuture<>(
                secondDelegate, value -> {
                }, failure -> {
                }, secondCancellations::incrementAndGet);
        assertTrue(second.cancel(true));
        assertTrue(secondDelegate.isCancelled());
        assertEquals(1, secondCancellations.get());
    }

    @Test
    void timeoutDoesNotMisclassifyRunningFutureAsTerminal() {
        var delegate = new CompletableFuture<String>();
        var terminalCallbacks = new AtomicInteger();
        var future = new TerminalAwareFuture<>(
                delegate,
                value -> terminalCallbacks.incrementAndGet(),
                failure -> terminalCallbacks.incrementAndGet(),
                terminalCallbacks::incrementAndGet);

        assertThrows(TimeoutException.class, () -> future.get(1, TimeUnit.MILLISECONDS));
        assertFalse(future.isDone());
        assertEquals(0, terminalCallbacks.get());
    }

    @Test
    void validationFailureDoesNotEscapeAe2IsDonePoll() {
        var delegate = CompletableFuture.completedFuture("unsafe-external-plan");
        var validationFailure = new IllegalArgumentException("unsafe plan");
        var observedFailure = new AtomicReference<Throwable>();
        var completions = new AtomicInteger();
        var future = new TerminalAwareFuture<>(
                delegate,
                result -> {
                    throw validationFailure;
                },
                result -> completions.incrementAndGet(),
                observedFailure::set,
                () -> {
                });

        assertTrue(future.isDone(), "AE2 polls this outside its exception handler");
        assertSame(validationFailure, observedFailure.get());
        assertEquals(0, completions.get());

        var thrown = assertThrows(ExecutionException.class, future::get);
        assertSame(validationFailure, thrown.getCause());
        assertSame(validationFailure, observedFailure.get());
    }

    @Test
    void terminalObserverFailuresNeverEscapeAe2IsDonePoll() {
        var completionFailure = new IllegalStateException("completion observer failed");
        var completionFailures = new AtomicInteger();
        var completed = new TerminalAwareFuture<>(
                CompletableFuture.completedFuture("plan"),
                result -> {
                    throw completionFailure;
                },
                failure -> completionFailures.incrementAndGet(),
                () -> {
                });
        assertDoesNotThrow(completed::isDone);
        assertEquals(1, completionFailures.get());
        assertSame(
                completionFailure,
                assertThrows(ExecutionException.class, completed::get).getCause());
        assertEquals(1, completionFailures.get());

        var plannerFailure = new IllegalArgumentException("planner failed");
        var failedDelegate = new CompletableFuture<String>();
        failedDelegate.completeExceptionally(plannerFailure);
        var failed = new TerminalAwareFuture<>(
                failedDelegate,
                result -> {
                },
                failure -> {
                    throw new IllegalStateException("failure observer failed");
                },
                () -> {
                });
        assertDoesNotThrow(failed::isDone);
        assertSame(
                plannerFailure,
                assertThrows(ExecutionException.class, failed::get).getCause());

        var cancelledDelegate = new CompletableFuture<String>();
        cancelledDelegate.cancel(true);
        var cancelled = new TerminalAwareFuture<>(
                cancelledDelegate,
                result -> {
                },
                failure -> {
                },
                () -> {
                    throw new IllegalStateException("cancellation observer failed");
                });
        assertDoesNotThrow(cancelled::isDone);
        assertThrows(CancellationException.class, cancelled::get);
    }
}
