package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AelisOverstrictSafetyRegressionTest {
    private static String plannerSource() throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/github/appliedenhancements/crafting/aelis/AelisPlanner.java"));
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

    @Test
    void stableUnknownPatternsAreAdmittedByStructureBeforeTypeFallback()
            throws IOException {
        String source = plannerSource();

        int snapshot = source.indexOf(
                "AelisObservedPatternSemantics.captureStable(details)");
        int guardedFallback = source.indexOf(
                "&& !structurallyStablePattern");
        assertTrue(snapshot >= 0);
        assertTrue(guardedFallback > snapshot);
        assertFalse(source.contains(
                "patternBarrierReason != null && quantityFeedbackCandidate"));
        assertTrue(source.contains(
                "patternBarrierReason == null\n"
                        + "                                || structurallyStablePattern"));
        assertTrue(source.contains(
                "candidate.observedPatternSemantics.matches"));
        assertTrue(source.contains(
                "return \"unknown_pattern_type:\" + details.getClass().getName()"));
    }

    @Test
    void quantityFeedbackExecutionUsesNetGrowthAndSkipsTheFeedbackChild()
            throws IOException {
        String source = plannerSource();

        assertTrue(source.contains("executeQuantityFeedbackNode("));
        assertTrue(source.contains("AelisQuantityFeedbackBatch.plan("));
        assertTrue(source.contains("consumable.quantityFeedbackInput"));
        assertTrue(source.contains("quantity_feedback_missing_seed"));
    }

    @Test
    void cyclicGraphsUseBoundedWholeGraphSolverBeforeRecursiveExecution()
            throws IOException {
        String source = plannerSource();

        int globalSolver = source.indexOf("tryExecuteGlobalCyclicPlan(");
        int recursiveExecution = source.indexOf("executeTransactionalNode(");
        assertTrue(globalSolver >= 0);
        assertTrue(recursiveExecution > globalSolver);
        assertTrue(source.contains("Config.CYCLE_SOLVER_MAX_SCC_NODES"));
        assertTrue(source.contains("Config.CYCLE_SOLVER_MAX_SEARCH_STATES"));
        assertTrue(source.contains("Config.CYCLE_SOLVER_BUDGET_MS"));
        assertFalse(source.contains("node.amount != 1 || node.emitter || node.barrier"));
    }
}
