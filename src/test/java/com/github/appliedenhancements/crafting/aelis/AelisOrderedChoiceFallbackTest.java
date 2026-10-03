package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AelisOrderedChoiceFallbackTest {

    @Test
    void unsafeSimulationResultCannotBeCommitted() {
        assertFalse(AelisOrderedChoiceFallback.mayCommitCompiledResult(
                true, false));
        assertTrue(AelisOrderedChoiceFallback.mayCommitCompiledResult(
                true, true));
        assertTrue(AelisOrderedChoiceFallback.mayCommitCompiledResult(
                false, false));
    }

}
