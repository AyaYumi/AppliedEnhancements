package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OmniOverstrictSafetyRegressionTest {
    private static String plannerSource() throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/github/appliedenhancements/crafting/maxfast/OmniMaxFastPlanner.java"));
    }

    @Test
    void deterministicDamageDoesNotRetainObsoleteSingleSlotRestrictions()
            throws IOException {
        String source = plannerSource();

        assertFalse(source.contains("\"multiple_damage_inputs\""));
        assertFalse(source.contains("inputMultiplier != 1"));
        assertTrue(source.contains("mayBatchDeterministicDamageSubstitute"));
    }

    @Test
    void everyNativeBoundaryUsesTheLinearWorkGuard() throws IOException {
        String source = plannerSource();

        assertTrue(source.contains("MAX_LINEAR_NATIVE_BOUNDARY_ITEMS"));
        assertTrue(source.contains("mayExecuteNativeBoundary"));
        assertTrue(source.contains("native_boundary_work_limit:"));
        assertTrue(source.contains("canSatisfyConsumableBoundaryInputFromStock"));
        assertTrue(source.contains("oversized_recursive_consumable_input:"));
    }

    @Test
    void invalidPatternOutputIsNeverGuessedAsOne() throws IOException {
        String source = plannerSource();

        assertFalse(source.contains("assuming outputPerPattern=1"));
        assertTrue(source.contains("throw new Fallback(\"invalid_output_per_pattern\")"));
    }
}
