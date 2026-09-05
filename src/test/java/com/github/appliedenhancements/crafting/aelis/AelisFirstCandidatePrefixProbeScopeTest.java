package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AelisFirstCandidatePrefixProbeScopeTest {
    @Test
    void nestsAndRestoresThreadLocalScope() {
        assertFalse(AelisFirstCandidatePrefixProbeScope.active());
        try (var outer = AelisFirstCandidatePrefixProbeScope.enter()) {
            assertTrue(AelisFirstCandidatePrefixProbeScope.active());
            try (var inner = AelisFirstCandidatePrefixProbeScope.enter()) {
                assertTrue(AelisFirstCandidatePrefixProbeScope.active());
            }
            assertTrue(AelisFirstCandidatePrefixProbeScope.active());
        }
        assertFalse(AelisFirstCandidatePrefixProbeScope.active());
    }

    @Test
    void closeIsIdempotent() {
        var scope = AelisFirstCandidatePrefixProbeScope.enter();
        scope.close();
        scope.close();
        assertFalse(AelisFirstCandidatePrefixProbeScope.active());
    }
}
