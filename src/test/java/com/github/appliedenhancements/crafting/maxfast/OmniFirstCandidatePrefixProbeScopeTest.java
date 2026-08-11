package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OmniFirstCandidatePrefixProbeScopeTest {
    @Test
    void nestsAndRestoresThreadLocalScope() {
        assertFalse(OmniFirstCandidatePrefixProbeScope.active());
        try (var outer = OmniFirstCandidatePrefixProbeScope.enter()) {
            assertTrue(OmniFirstCandidatePrefixProbeScope.active());
            try (var inner = OmniFirstCandidatePrefixProbeScope.enter()) {
                assertTrue(OmniFirstCandidatePrefixProbeScope.active());
            }
            assertTrue(OmniFirstCandidatePrefixProbeScope.active());
        }
        assertFalse(OmniFirstCandidatePrefixProbeScope.active());
    }

    @Test
    void closeIsIdempotent() {
        var scope = OmniFirstCandidatePrefixProbeScope.enter();
        scope.close();
        scope.close();
        assertFalse(OmniFirstCandidatePrefixProbeScope.active());
    }
}
