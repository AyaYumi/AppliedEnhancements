package com.appliedenhancements.runtime;

import com.appliedenhancements.api.AelisCraftingRequest;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;

/** Submission data visible while the host creates its task; this does not select a planner. */
public final class CraftingSubmissionScope implements AutoCloseable {
    private static final ThreadLocal<CraftingSubmissionScope> CURRENT = new ThreadLocal<>();
    private final CraftingSubmissionScope previous = CURRENT.get();
    private final AelisCraftingRequest request;
    private final CraftingCalculationProgressHandle progress;

    private CraftingSubmissionScope(AelisCraftingRequest request, CraftingCalculationProgressHandle progress) {
        this.request = request;
        this.progress = progress;
        CURRENT.set(this);
    }

    public static CraftingSubmissionScope withRequest(AelisCraftingRequest request) {
        return new CraftingSubmissionScope(request, progress());
    }

    public static CraftingSubmissionScope withProgress(CraftingCalculationProgressHandle progress) {
        return new CraftingSubmissionScope(request(), progress);
    }

    public static AelisCraftingRequest request() {
        var current = CURRENT.get();
        return current == null ? null : current.request;
    }

    public static CraftingCalculationProgressHandle progress() {
        var current = CURRENT.get();
        return current == null ? null : current.progress;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
