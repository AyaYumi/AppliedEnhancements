package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OmniMaxFastRecursionGuardTest {
    @Test
    void transactionalDepthLimitRejectsBeforeGrowingTheJavaStackFurther() {
        int entered = 0;
        try {
            for (int depth = 0;
                    depth < OmniMaxFastRecursionGuard.MAX_TRANSACTIONAL_DEPTH;
                    depth++) {
                assertTrue(OmniMaxFastRecursionGuard.tryEnterTransactional());
                entered++;
            }
            assertFalse(OmniMaxFastRecursionGuard.tryEnterTransactional());
            assertEquals(
                    OmniMaxFastRecursionGuard.MAX_TRANSACTIONAL_DEPTH,
                    OmniMaxFastRecursionGuard.currentTransactionalDepth());
        } finally {
            while (entered-- > 0) {
                OmniMaxFastRecursionGuard.exitTransactional();
            }
        }

        assertEquals(0, OmniMaxFastRecursionGuard.currentTransactionalDepth());
        assertTrue(OmniMaxFastRecursionGuard.tryEnterTransactional());
        OmniMaxFastRecursionGuard.exitTransactional();
    }
}
