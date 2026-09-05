package com.appliedenhancements.api;

/** Marker for CPU implementations that honor {@link AelisCycleExecutionPlan}. */
public interface AelisCycleAwareCpu {
    default boolean supportsAelisCycleExecution() {
        return true;
    }
}
