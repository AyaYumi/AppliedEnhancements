package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AelisSparseCapacitySolverTest {
    @Test
    void simulationAggregatesTerminalShortagesInsteadOfChangingCandidate() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 3))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(2, nodes);

        var plan = AelisSparseCapacitySolver.planSimulationFirstCandidate(
                model, 0, 1_000_000, new long[] { 0, 9 });

        assertTrue(plan.supported());
        assertTrue(plan.complete());
        assertEquals(0, plan.remaining());
        assertArrayEquals(new long[] { 1_000_000 },
                plan.candidateAllocations());
        assertArrayEquals(new long[] { 0, 2_999_991 },
                plan.simulatedMissing());
        assertArrayEquals(new long[] { 0, 0 }, plan.endingInventory());
        assertEquals(2, plan.probes(),
                "Simulation work must scale with graph nodes, not request size");
    }

    @Test
    void simulationConsumesSubstituteTemplatesBeforeRecordingMissingPrimary() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.substitutable(
                                        1, 1,
                                        new AelisSparseCapacitySolver.Template(2, 1)))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(3, nodes);

        var plan = AelisSparseCapacitySolver.planSimulationFirstCandidate(
                model, 0, 10, new long[] { 0, 0, 4 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 6, 0 },
                plan.simulatedMissing());
        assertArrayEquals(new long[] { 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void solvesHundredBillionRequestsWithoutQuantityExpansion() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 3))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(2, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 100_000_000_000L,
                new long[] { 0, 300_000_000_000L });

        assertTrue(plan.supported());
        assertTrue(plan.complete());
        assertEquals(0, plan.remaining());
        assertArrayEquals(new long[] { 100_000_000_000L },
                plan.candidateAllocations());
        assertEquals(0, plan.endingInventory()[1]);
    }

    @Test
    void sharesOneInventoryBucketAcrossRepeatedInputs() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 1),
                                AelisSparseCapacitySolver.Input.consumable(1, 1))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(2, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 6, new long[] { 0, 10 });

        assertTrue(plan.supported());
        assertFalse(plan.complete());
        assertEquals(1, plan.remaining());
        assertArrayEquals(new long[] { 5 }, plan.candidateAllocations());
    }

    @Test
    void sharedTerminalStockPreservesDifferentRequestUnits() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 1),
                                AelisSparseCapacitySolver.Input.consumable(2, 1))),
                AelisSparseCapacitySolver.Node.terminal(1, 1_000),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(2, nodes);

        var shortage = AelisSparseCapacitySolver.plan(
                model, 0, 1, new long[] { 0, 1_000 });
        var complete = AelisSparseCapacitySolver.plan(
                model, 0, 1, new long[] { 0, 1_001 });

        assertFalse(shortage.complete(),
                "The 1-unit request must not be folded into the 1000-unit request");
        assertTrue(complete.complete());
        assertEquals(0, complete.endingInventory()[1]);
    }

    @Test
    void preservesCeilDivisionAndCraftingSurplus() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                3,
                                AelisSparseCapacitySolver.Input.consumable(1, 1))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(2, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 4, new long[] { 0, 2 });

        assertTrue(plan.complete());
        assertEquals(2, plan.endingInventory()[0]);
        assertEquals(0, plan.endingInventory()[1]);
    }

    @Test
    void allocatesOrderedCandidatesAgainstSharedSnapshot() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 1)),
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(2, 1))),
                AelisSparseCapacitySolver.Node.terminal(1, 1),
                AelisSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(3, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 10, new long[] { 0, 3, 7 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 3, 7 }, plan.candidateAllocations());
        assertArrayEquals(new long[] { 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void prunesDirectNoProgressCandidateAndUsesNormalRecipe() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(0, 1)),
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 1))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(2, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 4, new long[] { 0, 4 });

        assertTrue(plan.supported());
        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 4 }, plan.candidateAllocations());
        assertEquals(1, plan.noProgressCandidatesSkipped());
    }

    @Test
    void componentStateCycleRejectsOnlyTheCyclicBranch() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 1)),
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(3, 1))),
                AelisSparseCapacitySolver.Node.craftable(
                        1, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(2, 1))),
                // A recursion-filtered occurrence of component 0. It has a
                // different graph node but the same inventory component.
                AelisSparseCapacitySolver.Node.terminal(0, 1),
                AelisSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(3, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 3, new long[] { 0, 0, 3 });

        assertTrue(plan.supported());
        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 3 }, plan.candidateAllocations());
        assertTrue(plan.noProgressCandidatesSkipped() >= 1);
    }

    @Test
    void simulationSkipsComponentCycleButNotOrdinaryShortage() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 1)),
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(3, 1))),
                AelisSparseCapacitySolver.Node.craftable(
                        1, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(2, 1))),
                AelisSparseCapacitySolver.Node.terminal(0, 1),
                AelisSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(3, nodes);

        var plan = AelisSparseCapacitySolver.planSimulationFirstCandidate(
                model, 0, 5, new long[] { 0, 0, 0 });

        assertTrue(plan.supported());
        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 5 }, plan.candidateAllocations());
        assertArrayEquals(new long[] { 0, 0, 5 }, plan.simulatedMissing());
        assertTrue(plan.noProgressCandidatesSkipped() >= 1);
    }

    @Test
    void allocatesDirectStockCandidatesWithDifferentOutputs() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                3,
                                AelisSparseCapacitySolver.Input.consumable(1, 1)),
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(2, 1))),
                AelisSparseCapacitySolver.Node.terminal(1, 1),
                AelisSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(3, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 10, new long[] { 0, 2, 4 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 6, 4 }, plan.candidateAllocations());
        assertArrayEquals(new long[] { 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void allocatesNestedOrderedCandidatesWithDifferentOutputs() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(1, 1))),
                AelisSparseCapacitySolver.Node.craftable(
                        1, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.consumable(2, 1)),
                        new AelisSparseCapacitySolver.Candidate(
                                4,
                                AelisSparseCapacitySolver.Input.consumable(3, 1))),
                AelisSparseCapacitySolver.Node.terminal(2, 1),
                AelisSparseCapacitySolver.Node.terminal(3, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(4, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 10, new long[] { 0, 0, 2, 2 });

        assertTrue(plan.supported());
        assertTrue(plan.complete());
        assertEquals(0, plan.remaining());
        assertArrayEquals(new long[] { 10 }, plan.candidateAllocations());
        assertArrayEquals(new long[] { 0, 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void equivalentCandidatesCannotSpendTheSameCapacityTwice() {
        var first = new AelisSparseCapacitySolver.Candidate(
                1, AelisSparseCapacitySolver.Input.consumable(1, 1));
        var duplicate = new AelisSparseCapacitySolver.Candidate(
                1, AelisSparseCapacitySolver.Input.consumable(1, 1));
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(0, 1, first, duplicate),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(2, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 5, new long[] { 0, 3 });

        assertFalse(plan.complete());
        assertEquals(2, plan.remaining());
        assertArrayEquals(new long[] { 3, 0 }, plan.candidateAllocations());
        assertEquals(1, plan.equivalentCandidatesSkipped());
    }

    @Test
    void duplicateCandidateChainsStayLinearInDepth() {
        int levels = 32;
        var nodes = new AelisSparseCapacitySolver.Node[levels + 1];
        nodes[levels] = AelisSparseCapacitySolver.Node.terminal(levels, 1);
        for (int level = levels - 1; level >= 0; level--) {
            var first = new AelisSparseCapacitySolver.Candidate(
                    1,
                    AelisSparseCapacitySolver.Input.consumable(level + 1, 1));
            var duplicate = new AelisSparseCapacitySolver.Candidate(
                    1,
                    AelisSparseCapacitySolver.Input.consumable(level + 1, 1));
            nodes[level] = AelisSparseCapacitySolver.Node.craftable(
                    level, 1, first, duplicate);
        }
        var model = new AelisSparseCapacitySolver.Model(levels + 1, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 100_000_000_000L,
                inventoryWithTerminalAmount(levels + 1, 1_000_000));

        assertTrue(plan.supported());
        assertFalse(plan.complete());
        assertTrue(plan.probes() < 5_000,
                () -> "duplicate chain used " + plan.probes() + " probes");
    }

    private static long[] inventoryWithTerminalAmount(
            int keyCount, long amount) {
        var inventory = new long[keyCount];
        inventory[keyCount - 1] = amount;
        return inventory;
    }

    @Test
    void invariantReusableInputIsLeasedOncePerBatch() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.reusable(1, 2),
                                AelisSparseCapacitySolver.Input.consumable(1, 1))),
                AelisSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(3, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 4, new long[] { 0, 2, 4 });

        assertTrue(plan.complete());
        assertEquals(2, plan.endingInventory()[1]);
        assertEquals(0, plan.endingInventory()[2]);
    }

    @Test
    void substituteTemplatesAreConsumedInAe2OrderBeforeCraftingRemainder() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.substitutable(
                                        1, 1,
                                        new AelisSparseCapacitySolver.Template(2, 1),
                                        new AelisSparseCapacitySolver.Template(3, 2)))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(4, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 7, new long[] { 0, 1, 3, 6 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void substituteTemplateRoundingDoesNotConsumePartialUnits() {
        var nodes = new AelisSparseCapacitySolver.Node[] {
                AelisSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new AelisSparseCapacitySolver.Candidate(
                                1,
                                AelisSparseCapacitySolver.Input.substitutable(
                                        1, 1,
                                        new AelisSparseCapacitySolver.Template(2, 2)))),
                AelisSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new AelisSparseCapacitySolver.Model(3, nodes);

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 3, new long[] { 0, 2, 3 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 0, 1 }, plan.endingInventory());
    }
}
