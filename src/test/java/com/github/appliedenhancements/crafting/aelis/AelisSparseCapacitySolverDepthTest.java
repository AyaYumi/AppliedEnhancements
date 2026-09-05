package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class AelisSparseCapacitySolverDepthTest {
    @Test
    void deepCraftingChainReturnsUnsupportedInsteadOfOverflowingTheStack() {
        int levels = AelisSparseCapacitySolver.MAX_NODE_DEPTH + 64;
        var nodes = new AelisSparseCapacitySolver.Node[levels + 1];
        nodes[levels] = AelisSparseCapacitySolver.Node.terminal(levels, 1);
        for (int level = levels - 1; level >= 0; level--) {
            nodes[level] = AelisSparseCapacitySolver.Node.craftable(
                    level,
                    1,
                    new AelisSparseCapacitySolver.Candidate(
                            1,
                            AelisSparseCapacitySolver.Input.consumable(
                                    level + 1, 1)));
        }
        var model = new AelisSparseCapacitySolver.Model(levels + 1, nodes);
        var initialInventory = new long[levels + 1];
        initialInventory[levels] = 1;

        var plan = AelisSparseCapacitySolver.plan(
                model, 0, 1, initialInventory);

        assertFalse(plan.supported());
        assertFalse(plan.complete());
        assertArrayEquals(initialInventory, plan.endingInventory());
    }
}
