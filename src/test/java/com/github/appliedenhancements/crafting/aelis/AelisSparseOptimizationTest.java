package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.*;
import static com.github.appliedenhancements.crafting.aelis.AelisSparseCapacitySolver.Input.*;
import static com.github.appliedenhancements.crafting.aelis.AelisSparseCapacitySolver.Node.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class AelisSparseOptimizationTest {
    @Test void rawCapacityAggregatesSlotsAndPreservesRoundingWithBoundedProbes() {
        var model = new AelisSparseCapacitySolver.Model(3,
                craftable(0, 2, new AelisSparseCapacitySolver.Candidate(3,
                        consumable(1, 2), consumable(1, 1), consumable(2, 2))),
                terminal(1, 1), terminal(2, 1));
        long[] inventory = {3, 30, 14};
        var plan = AelisSparseCapacitySolver.plan(model, 0, 100_000_000_000L, inventory);
        assertTrue(plan.supported());
        assertFalse(plan.complete());
        assertArrayEquals(new long[] {11}, plan.candidateAllocations());
        assertArrayEquals(new long[] {2, 9, 0}, plan.endingInventory());
        assertArrayEquals(new long[] {3, 30, 14}, inventory);
        assertTrue(plan.probes() <= 2, () -> "used " + plan.probes() + " probes");
    }

    @Test void failedNestedCandidateRestoresMaterialsAndReusableLeases() {
        var model = new AelisSparseCapacitySolver.Model(5,
                craftable(0, 1,
                        new AelisSparseCapacitySolver.Candidate(1, reusable(4, 1),
                                consumable(1, 1), consumable(3, 1)),
                        new AelisSparseCapacitySolver.Candidate(1, reusable(4, 1), consumable(2, 1))),
                craftable(1, 1, new AelisSparseCapacitySolver.Candidate(2, consumable(2, 2))),
                terminal(2, 1), terminal(3, 1));
        long[] inventory = {0, 0, 5, 0, 1};
        var plan = AelisSparseCapacitySolver.plan(model, 0, 5, inventory);
        assertTrue(plan.complete());
        assertArrayEquals(new long[] {0, 5}, plan.candidateAllocations());
        assertArrayEquals(new long[] {0, 0, 0, 0, 1}, plan.endingInventory());
        assertArrayEquals(new long[] {0, 0, 5, 0, 1}, inventory);
    }

    @Test void simulationRollsBackMissingAndStockBeforeSkippingAnIndirectNoProgressCycle() {
        var model = new AelisSparseCapacitySolver.Model(4,
                craftable(0, 1,
                        new AelisSparseCapacitySolver.Candidate(2,
                                consumable(2, 1), consumable(3, 1), consumable(1, 1)),
                        new AelisSparseCapacitySolver.Candidate(1, consumable(2, 1))),
                craftable(1, 1, new AelisSparseCapacitySolver.Candidate(1, consumable(0, 2))),
                terminal(2, 1), terminal(3, 1));
        var plan = AelisSparseCapacitySolver.planSimulationFirstCandidate(
                model, 0, 8, new long[] {0, 0, 1, 0});
        assertTrue(plan.complete());
        assertArrayEquals(new long[] {0, 8}, plan.candidateAllocations());
        assertArrayEquals(new long[] {0, 0, 7, 0}, plan.simulatedMissing());
        assertArrayEquals(new long[4], plan.endingInventory());
        assertTrue(plan.noProgressCandidatesSkipped() > 0);
    }

    @Test void rawCandidateMixMatchesOrderedCapacityForRandomSharedStock() {
        var random = new Random(413);
        for (int trial = 0; trial < 250; trial++) {
            int output = random.nextInt(5) + 1;
            int cost = random.nextInt(5) + 1;
            int stock = random.nextInt(60);
            int requested = random.nextInt(80) + 1;
            var model = new AelisSparseCapacitySolver.Model(3,
                    craftable(0, 1,
                            new AelisSparseCapacitySolver.Candidate(output, consumable(1, cost)),
                            new AelisSparseCapacitySolver.Candidate(1, consumable(2, 1))),
                    terminal(1, 1), terminal(2, 1));
            var plan = AelisSparseCapacitySolver.plan(model, 0, requested,
                    new long[] {0, stock, requested});
            long first = Math.min(requested, (stock / cost) * output);
            long surplus = first == 0 ? 0 : (output - first % output) % output;
            long second = requested - first;
            assertTrue(plan.complete());
            assertArrayEquals(new long[] {first, second}, plan.candidateAllocations());
            // Existing surplus is consumed by the next candidate before it crafts.
            assertEquals(stock - ((first + output - 1) / output) * cost,
                    plan.endingInventory()[1]);
            assertEquals(requested - Math.max(0, second - surplus), plan.endingInventory()[2]);
        }
    }
}
