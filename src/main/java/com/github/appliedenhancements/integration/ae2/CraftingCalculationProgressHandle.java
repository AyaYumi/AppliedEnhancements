package com.github.appliedenhancements.integration.ae2;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CraftingCalculationProgressHandle {
    private final long generation;
    private final long startedAtNanos = System.nanoTime();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicLong processedSteps = new AtomicLong();
    private final AtomicLong discoveredNodes = new AtomicLong();
    private final AtomicLong completedUnits = new AtomicLong();

    private volatile CraftingCalculationProgressPhase phase =
            CraftingCalculationProgressPhase.QUEUED;
    private volatile OmniCalculationPath path = OmniCalculationPath.AE2_NATIVE;
    private volatile long totalUnits = -1;
    private volatile long finishedElapsedNanos = -1;
    private volatile int attempt;
    private volatile boolean simulation;

    public CraftingCalculationProgressHandle(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        this.generation = generation;
    }

    public long generation() {
        return generation;
    }

    public synchronized void waitingForSlot() {
        setPhase(CraftingCalculationProgressPhase.WAITING_SLOT);
    }

    public synchronized void beginAttempt(boolean simulation) {
        if (terminal.get()) {
            return;
        }
        this.simulation = simulation;
        this.attempt++;
        this.completedUnits.set(0);
        this.totalUnits = -1;
        this.phase = CraftingCalculationProgressPhase.PREPARING;
    }

    public synchronized void beginMaxFastCompilation() {
        if (terminal.get()) {
            return;
        }
        this.path = OmniCalculationPath.MAX_FAST;
        this.completedUnits.set(0);
        this.totalUnits = -1;
        this.phase = CraftingCalculationProgressPhase.MAX_FAST_COMPILING;
    }

    public synchronized void beginMaxFastExecution(long totalUnits) {
        if (terminal.get()) {
            return;
        }
        this.path = OmniCalculationPath.MAX_FAST;
        this.completedUnits.set(0);
        this.totalUnits = Math.max(-1, totalUnits);
        this.phase = CraftingCalculationProgressPhase.MAX_FAST_EXECUTING;
    }

    public synchronized void beginAe2(OmniCalculationPath path) {
        if (terminal.get()) {
            return;
        }
        if (path != OmniCalculationPath.AE2_NATIVE
                && path != OmniCalculationPath.AE2_FALLBACK) {
            throw new IllegalArgumentException("AE2 progress requires a native or fallback path");
        }
        this.path = path;
        this.completedUnits.set(0);
        this.totalUnits = -1;
        this.phase = CraftingCalculationProgressPhase.AE2_CALCULATING;
    }

    public synchronized void beginBuildingPlan() {
        setPhase(CraftingCalculationProgressPhase.BUILDING_PLAN);
    }

    public void nodeDiscovered() {
        if (!terminal.get()) {
            discoveredNodes.incrementAndGet();
        }
    }

    public void workStep() {
        if (!terminal.get()) {
            processedSteps.incrementAndGet();
        }
    }

    public void executionStep() {
        if (!terminal.get()) {
            processedSteps.incrementAndGet();
            completedUnits.incrementAndGet();
        }
    }

    public synchronized void complete(OmniCalculationPath finalPath) {
        if (terminal.compareAndSet(false, true)) {
            this.path = finalPath == null ? this.path : finalPath;
            long total = this.totalUnits;
            if (total >= 0) {
                this.completedUnits.set(total);
            }
            this.finishedElapsedNanos = elapsedNanos();
            this.phase = CraftingCalculationProgressPhase.COMPLETED;
        }
    }

    public synchronized void cancel() {
        finishTerminal(CraftingCalculationProgressPhase.CANCELLED);
    }

    public synchronized void fail() {
        finishTerminal(CraftingCalculationProgressPhase.FAILED);
    }

    public boolean terminal() {
        return terminal.get();
    }

    public synchronized CraftingCalculationProgressSnapshot snapshot(long revision) {
        long total = totalUnits;
        long completed = Math.max(0, completedUnits.get());
        if (total >= 0) {
            completed = Math.min(completed, total);
        }
        long elapsedNanos = finishedElapsedNanos;
        if (elapsedNanos < 0) {
            elapsedNanos = elapsedNanos();
        }
        return new CraftingCalculationProgressSnapshot(
                generation,
                revision,
                phase,
                path,
                Math.max(0, processedSteps.get()),
                Math.max(0, discoveredNodes.get()),
                completed,
                total,
                Math.max(0, elapsedNanos / 1_000_000L),
                Math.max(0, attempt),
                simulation);
    }

    private void setPhase(CraftingCalculationProgressPhase phase) {
        if (!terminal.get()) {
            this.phase = phase;
        }
    }

    private void finishTerminal(CraftingCalculationProgressPhase terminalPhase) {
        if (terminal.compareAndSet(false, true)) {
            this.finishedElapsedNanos = elapsedNanos();
            this.phase = terminalPhase;
        }
    }

    private long elapsedNanos() {
        return Math.max(0, System.nanoTime() - startedAtNanos);
    }
}
