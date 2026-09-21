package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AelisPlanningLimitExceptionTest {
    @Test void neverReplaysArithmeticOrWorkLimitFailuresEvenForSmallRootOrders() {
        assertTrue(AelisPlanningLimitException.rejectsNativeFallback("child_request_overflow", 1));
        assertTrue(AelisPlanningLimitException.rejectsNativeFallback("local_cyclic_firing_overflow", 1));
        assertTrue(AelisPlanningLimitException.rejectsNativeFallback("native_boundary_work_limit:container_items", 1));
        assertTrue(AelisPlanningLimitException.rejectsNativeFallback("compile_budget", Long.MAX_VALUE));
        assertFalse(AelisPlanningLimitException.rejectsNativeFallback("unsupported_pattern", 64));
    }
}
