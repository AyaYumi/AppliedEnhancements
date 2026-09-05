package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AelisRecursionGuardTest {
    @Test
    void transactionalDepthLimitRejectsBeforeGrowingTheJavaStackFurther() {
        int entered = 0;
        try {
            for (int depth = 0;
                    depth < AelisRecursionGuard.MAX_TRANSACTIONAL_DEPTH;
                    depth++) {
                assertTrue(AelisRecursionGuard.tryEnterTransactional());
                entered++;
            }
            assertFalse(AelisRecursionGuard.tryEnterTransactional());
            assertEquals(
                    AelisRecursionGuard.MAX_TRANSACTIONAL_DEPTH,
                    AelisRecursionGuard.currentTransactionalDepth());
        } finally {
            while (entered-- > 0) {
                AelisRecursionGuard.exitTransactional();
            }
        }

        assertEquals(0, AelisRecursionGuard.currentTransactionalDepth());
        assertTrue(AelisRecursionGuard.tryEnterTransactional());
        AelisRecursionGuard.exitTransactional();
    }
}
