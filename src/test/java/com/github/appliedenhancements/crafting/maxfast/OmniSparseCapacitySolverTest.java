package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OmniSparseCapacitySolverTest {
    @Test
    void simulationAggregatesTerminalShortagesInsteadOfChangingCandidate() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.consumable(1, 3))),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(2, nodes);

        var plan = OmniSparseCapacitySolver.planSimulationFirstCandidate(
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
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.substitutable(
                                        1, 1,
                                        new OmniSparseCapacitySolver.Template(2, 1)))),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(3, nodes);

        var plan = OmniSparseCapacitySolver.planSimulationFirstCandidate(
                model, 0, 10, new long[] { 0, 0, 4 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 6, 0 },
                plan.simulatedMissing());
        assertArrayEquals(new long[] { 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void solvesHundredBillionRequestsWithoutQuantityExpansion() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.consumable(1, 3))),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(2, nodes);

        var plan = OmniSparseCapacitySolver.plan(
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
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.consumable(1, 1),
                                OmniSparseCapacitySolver.Input.consumable(1, 1))),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(2, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 6, new long[] { 0, 10 });

        assertTrue(plan.supported());
        assertFalse(plan.complete());
        assertEquals(1, plan.remaining());
        assertArrayEquals(new long[] { 5 }, plan.candidateAllocations());
    }

    @Test
    void sharedTerminalStockPreservesDifferentRequestUnits() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.consumable(1, 1),
                                OmniSparseCapacitySolver.Input.consumable(2, 1))),
                OmniSparseCapacitySolver.Node.terminal(1, 1_000),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(2, nodes);

        var shortage = OmniSparseCapacitySolver.plan(
                model, 0, 1, new long[] { 0, 1_000 });
        var complete = OmniSparseCapacitySolver.plan(
                model, 0, 1, new long[] { 0, 1_001 });

        assertFalse(shortage.complete(),
                "The 1-unit request must not be folded into the 1000-unit request");
        assertTrue(complete.complete());
        assertEquals(0, complete.endingInventory()[1]);
    }

    @Test
    void preservesCeilDivisionAndCraftingSurplus() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                3,
                                OmniSparseCapacitySolver.Input.consumable(1, 1))),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(2, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 4, new long[] { 0, 2 });

        assertTrue(plan.complete());
        assertEquals(2, plan.endingInventory()[0]);
        assertEquals(0, plan.endingInventory()[1]);
    }

    @Test
    void allocatesOrderedCandidatesAgainstSharedSnapshot() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.consumable(1, 1)),
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.consumable(2, 1))),
                OmniSparseCapacitySolver.Node.terminal(1, 1),
                OmniSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(3, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 10, new long[] { 0, 3, 7 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 3, 7 }, plan.candidateAllocations());
        assertArrayEquals(new long[] { 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void allocatesDirectStockCandidatesWithDifferentOutputs() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                3,
                                OmniSparseCapacitySolver.Input.consumable(1, 1)),
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.consumable(2, 1))),
                OmniSparseCapacitySolver.Node.terminal(1, 1),
                OmniSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(3, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 10, new long[] { 0, 2, 4 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 6, 4 }, plan.candidateAllocations());
        assertArrayEquals(new long[] { 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void equivalentCandidatesCannotSpendTheSameCapacityTwice() {
        var first = new OmniSparseCapacitySolver.Candidate(
                1, OmniSparseCapacitySolver.Input.consumable(1, 1));
        var duplicate = new OmniSparseCapacitySolver.Candidate(
                1, OmniSparseCapacitySolver.Input.consumable(1, 1));
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(0, 1, first, duplicate),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(2, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 5, new long[] { 0, 3 });

        assertFalse(plan.complete());
        assertEquals(2, plan.remaining());
        assertArrayEquals(new long[] { 3, 0 }, plan.candidateAllocations());
        assertEquals(1, plan.equivalentCandidatesSkipped());
    }

    @Test
    void duplicateCandidateChainsStayLinearInDepth() {
        int levels = 32;
        var nodes = new OmniSparseCapacitySolver.Node[levels + 1];
        nodes[levels] = OmniSparseCapacitySolver.Node.terminal(levels, 1);
        for (int level = levels - 1; level >= 0; level--) {
            var first = new OmniSparseCapacitySolver.Candidate(
                    1,
                    OmniSparseCapacitySolver.Input.consumable(level + 1, 1));
            var duplicate = new OmniSparseCapacitySolver.Candidate(
                    1,
                    OmniSparseCapacitySolver.Input.consumable(level + 1, 1));
            nodes[level] = OmniSparseCapacitySolver.Node.craftable(
                    level, 1, first, duplicate);
        }
        var model = new OmniSparseCapacitySolver.Model(levels + 1, nodes);

        var plan = OmniSparseCapacitySolver.plan(
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
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.reusable(1, 2),
                                OmniSparseCapacitySolver.Input.consumable(1, 1))),
                OmniSparseCapacitySolver.Node.terminal(2, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(3, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 4, new long[] { 0, 2, 4 });

        assertTrue(plan.complete());
        assertEquals(2, plan.endingInventory()[1]);
        assertEquals(0, plan.endingInventory()[2]);
    }

    @Test
    void substituteTemplatesAreConsumedInAe2OrderBeforeCraftingRemainder() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.substitutable(
                                        1, 1,
                                        new OmniSparseCapacitySolver.Template(2, 1),
                                        new OmniSparseCapacitySolver.Template(3, 2)))),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(4, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 7, new long[] { 0, 1, 3, 6 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 0, 0, 0 }, plan.endingInventory());
    }

    @Test
    void substituteTemplateRoundingDoesNotConsumePartialUnits() {
        var nodes = new OmniSparseCapacitySolver.Node[] {
                OmniSparseCapacitySolver.Node.craftable(
                        0, 1,
                        new OmniSparseCapacitySolver.Candidate(
                                1,
                                OmniSparseCapacitySolver.Input.substitutable(
                                        1, 1,
                                        new OmniSparseCapacitySolver.Template(2, 2)))),
                OmniSparseCapacitySolver.Node.terminal(1, 1)
        };
        var model = new OmniSparseCapacitySolver.Model(3, nodes);

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 3, new long[] { 0, 2, 3 });

        assertTrue(plan.complete());
        assertArrayEquals(new long[] { 0, 0, 1 }, plan.endingInventory());
    }
}
