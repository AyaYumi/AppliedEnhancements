package com.appliedenhancements.runtime;

import com.appliedenhancements.Config;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;

/** Whether Applied Enhancements may alter the AE2 crafting calculation path. */
public final class CraftingPlannerIntervention {
    private static final ThreadLocal<Scope> EXPLICIT = new ThreadLocal<>();
    private CraftingPlannerIntervention() {
    }

    public static boolean enabled() {
        return EXPLICIT.get() != null || ExactRequestScope.current() != null
                || Config.ENABLE_AUTOMATIC_AELIS_PLANNER.get();
    }

    /** Explicitly produced state/plan metadata outlives the calculation thread scope. */
    public static boolean enabledFor(Object stateOrPlan) {
        return (stateOrPlan instanceof AelisCalculationPathCarrier source
                && source.molecularmanipulator$getCalculationPath() == AelisCalculationPath.AELIS)
                || enabled();
    }

    /** A native subrequest is contributing to a disposable AELIS simulation state. */
    public static boolean stagesMissing() {
        var scope = EXPLICIT.get();
        return scope != null && scope.stageMissing;
    }

    /** The planner publishes missing items only after its inventory transaction commits. */
    public static Scope openPlanning() {
        return new Scope(true);
    }

    /** Internal scope owned by public API entry points, never by callers. */
    public static Scope openExplicit() {
        return new Scope(stagesMissing());
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous = EXPLICIT.get();
        private final Thread owner = Thread.currentThread();
        private final boolean stageMissing;
        private boolean closed;

        private Scope(boolean stageMissing) {
            this.stageMissing = stageMissing;
            EXPLICIT.set(this);
        }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) return;
            closed = true;
            if (EXPLICIT.get() != this) return;
            var next = previous;
            while (next != null && next.closed) next = next.previous;
            if (next == null) EXPLICIT.remove(); else EXPLICIT.set(next);
        }
    }
}
