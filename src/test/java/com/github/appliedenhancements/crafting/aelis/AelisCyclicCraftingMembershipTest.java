package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisCyclicCraftingMembershipTest {
    @Test
    void excludesAcyclicPatternsFromAWholeGraphCyclicSolve() {
        var variants = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        variants.put("root", List.of(variant(
                "makeRoot", "root", input("A"), input("D"))));
        variants.put("A", List.of(
                variant("makeAFromCycle", "A", input("C"), input("B")),
                variant("makeADirect", "A", input("X"))));
        variants.put("C", List.of(variant(
                "makeCFromCycle", "C", input("A"), input("B"))));
        variants.put("D", List.of(variant("makeD", "D", input("E"))));

        Set<String> cyclic = AelisCyclicCraftingMembership.find(variants);

        assertEquals(Set.of("makeAFromCycle", "makeCFromCycle"), cyclic);
    }

    @SafeVarargs
    private static AelisCyclicDemandSolver.Variant<String, String> variant(
            String id,
            String output,
            AelisCyclicDemandSolver.Input<String>... inputs) {
        return new AelisCyclicDemandSolver.Variant<>(
                id, output, BigInteger.ONE, List.of(inputs));
    }

    private static AelisCyclicDemandSolver.Input<String> input(String key) {
        return new AelisCyclicDemandSolver.Input<>(key, BigInteger.ONE);
    }
}
