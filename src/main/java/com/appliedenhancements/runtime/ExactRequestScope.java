package com.appliedenhancements.runtime;

import com.appliedenhancements.api.AelisExactRequest;

/** Calculation-thread context, restored even when the calculation is cancelled or fails. */
public final class ExactRequestScope implements AutoCloseable {
    private static final ThreadLocal<AelisExactRequest> CURRENT = new ThreadLocal<>();
    private final AelisExactRequest previous;
    public ExactRequestScope(AelisExactRequest request) { previous = CURRENT.get(); CURRENT.set(request); }
    public static AelisExactRequest current() { return CURRENT.get(); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
