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

    /** Internal scope owned by public API entry points, never by callers. */
    public static Scope openExplicit() {
        return new Scope();
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous = EXPLICIT.get();
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Scope() { EXPLICIT.set(this); }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || EXPLICIT.get() != this) {
                throw new IllegalStateException("Planning scopes must close on their owner thread in nesting order");
            }
            if (previous == null) EXPLICIT.remove(); else EXPLICIT.set(previous);
            closed = true;
        }
    }
}
