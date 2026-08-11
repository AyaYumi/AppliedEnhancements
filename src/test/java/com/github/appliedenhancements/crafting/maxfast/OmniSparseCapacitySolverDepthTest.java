package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class OmniSparseCapacitySolverDepthTest {
    @Test
    void deepCraftingChainReturnsUnsupportedInsteadOfOverflowingTheStack() {
        int levels = OmniSparseCapacitySolver.MAX_NODE_DEPTH + 64;
        var nodes = new OmniSparseCapacitySolver.Node[levels + 1];
        nodes[levels] = OmniSparseCapacitySolver.Node.terminal(levels, 1);
        for (int level = levels - 1; level >= 0; level--) {
            nodes[level] = OmniSparseCapacitySolver.Node.craftable(
                    level,
                    1,
                    new OmniSparseCapacitySolver.Candidate(
                            1,
                            OmniSparseCapacitySolver.Input.consumable(
                                    level + 1, 1)));
        }
        var model = new OmniSparseCapacitySolver.Model(levels + 1, nodes);
        var initialInventory = new long[levels + 1];
        initialInventory[levels] = 1;

        var plan = OmniSparseCapacitySolver.plan(
                model, 0, 1, initialInventory);

        assertFalse(plan.supported());
        assertFalse(plan.complete());
        assertArrayEquals(initialInventory, plan.endingInventory());
    }
}
