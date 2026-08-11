package com.appliedenhancements.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.jetbrains.annotations.ApiStatus;

/**
 * Observes a future's terminal state without adding another worker thread.
 *
 * <p>AE2 polls crafting jobs with {@link Future#isDone()} before calling
 * {@link Future#get()}, so observing both methods also covers futures returned by
 * external crafting planners that do not execute AE2's CraftingCalculation.run().
 */
@ApiStatus.Internal
public final class TerminalAwareFuture<T> implements Future<T> {
    private final Future<T> delegate;
    private final Consumer<? super T> completed;
    private final Consumer<? super Throwable> failed;
    private final Runnable cancelled;
    private final Object terminalObservationLock = new Object();
    private boolean terminalObserved;
    private Throwable successfulObservationFailure;

    public TerminalAwareFuture(
            Future<T> delegate,
            Consumer<? super T> completed,
            Consumer<? super Throwable> failed,
            Runnable cancelled) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.completed = Objects.requireNonNull(completed, "completed");
        this.failed = Objects.requireNonNull(failed, "failed");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    }

    /**
     * Creates an observed future whose successful result must first pass a
     * validator. A validation exception is cached as an {@link ExecutionException}:
     * {@link #isDone()} remains a non-throwing terminal poll and the following
     * {@link #get()} exposes the failure through the normal Future contract.
     */
    public TerminalAwareFuture(
            Future<T> delegate,
            Consumer<? super T> validator,
            Consumer<? super T> completed,
            Consumer<? super Throwable> failed,
            Runnable cancelled) {
        this(new ValidatingFuture<>(delegate, validator), completed, failed, cancelled);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean result = delegate.cancel(mayInterruptIfRunning);
        if (result) {
            observeCancellation();
        } else if (delegate.isDone()) {
            observeCompletedDelegate();
        }
        return result;
    }

    @Override
    public boolean isCancelled() {
        boolean result = delegate.isCancelled();
        if (result) {
            observeCancellation();
        }
        return result;
    }

    @Override
    public boolean isDone() {
        boolean result = delegate.isDone();
        if (result) {
            observeCompletedDelegate();
        }
        return result;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        try {
            T result = delegate.get();
            Throwable observationFailure = observeSuccess(result);
            if (observationFailure != null) {
                throw new ExecutionException(observationFailure);
            }
            return result;
        } catch (CancellationException exception) {
            observeCancellation();
            throw exception;
        } catch (ExecutionException exception) {
            observeFailure(exception.getCause() == null ? exception : exception.getCause());
            throw exception;
        }
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            T result = delegate.get(timeout, unit);
            Throwable observationFailure = observeSuccess(result);
            if (observationFailure != null) {
                throw new ExecutionException(observationFailure);
            }
            return result;
        } catch (CancellationException exception) {
            observeCancellation();
            throw exception;
        } catch (ExecutionException exception) {
            observeFailure(exception.getCause() == null ? exception : exception.getCause());
            throw exception;
        }
    }

    private void observeCompletedDelegate() {
        try {
            T result = delegate.get();
            observeSuccess(result);
        } catch (CancellationException exception) {
            observeCancellation();
        } catch (ExecutionException exception) {
            observeFailure(exception.getCause() == null ? exception : exception.getCause());
        } catch (InterruptedException exception) {
            // isDone() made get() non-blocking. Preserve the caller's interrupt and
            // leave the future unobserved so a later server tick can classify it.
            Thread.currentThread().interrupt();
        }
    }

    private Throwable observeSuccess(T result) {
        synchronized (terminalObservationLock) {
            if (!terminalObserved) {
                terminalObserved = true;
                try {
                    completed.accept(result);
                } catch (Throwable failure) {
                    successfulObservationFailure = failure;
                    try {
                        failed.accept(failure);
                    } catch (Throwable ignored) {
                        // The completion-observer failure remains the outcome.
                    }
                }
            }
            return successfulObservationFailure;
        }
    }

    private void observeFailure(Throwable failure) {
        synchronized (terminalObservationLock) {
            if (!terminalObserved) {
                terminalObserved = true;
                try {
                    failed.accept(failure);
                } catch (Throwable ignored) {
                    // The delegate failure remains the Future's primary outcome.
                }
            }
        }
    }

    private void observeCancellation() {
        synchronized (terminalObservationLock) {
            if (!terminalObserved) {
                terminalObserved = true;
                try {
                    cancelled.run();
                } catch (Throwable ignored) {
                    // Cancellation remains the Future's primary outcome.
                }
            }
        }
    }

    private static final class ValidatingFuture<T> implements Future<T> {
        private final Future<T> delegate;
        private final Consumer<? super T> validator;
        private final Object validationLock = new Object();
        private volatile boolean validationComplete;
        private Throwable validationFailure;

        private ValidatingFuture(Future<T> delegate, Consumer<? super T> validator) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.validator = Objects.requireNonNull(validator, "validator");
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            T result = delegate.get();
            validate(result);
            return result;
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            T result = delegate.get(timeout, unit);
            validate(result);
            return result;
        }

        private void validate(T result) throws ExecutionException {
            if (!validationComplete) {
                synchronized (validationLock) {
                    if (!validationComplete) {
                        try {
                            validator.accept(result);
                        } catch (Throwable failure) {
                            validationFailure = failure;
                        } finally {
                            validationComplete = true;
                        }
                    }
                }
            }
            if (validationFailure != null) {
                throw new ExecutionException(validationFailure);
            }
        }
    }
}
