package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisCyclicRegionDetectorTest {
    @Test
    void isolatesMeteorSeedCycleFromUnrelatedUnsupportedBranch() {
        var models = new LinkedHashMap<String,
                AelisCyclicRegionDetector.KeyModel<String, String>>();
        models.put("root", model(true,
                variant("root", "root", 1,
                        input("meteorSeed", 1), input("machineBoundary", 1))));
        models.put("meteorSeed", model(true,
                variant("aggregator", "meteorSeed", 32,
                        input("meteorDust", 8), input("certusDust", 8),
                        input("gravel", 16)),
                variant("craftingTable", "meteorSeed", 1,
                        input("meteorDust", 1), input("certusDust", 1),
                        input("gravel", 2))));
        models.put("pureMeteor", model(true,
                variant("grow", "pureMeteor", 1, input("meteorSeed", 1))));
        models.put("meteorDust", model(true,
                variant("pulverize", "meteorDust", 1, input("pureMeteor", 1))));
        models.put("machineBoundary", model(false,
                variant("unsupported", "machineBoundary", 1, input("iron", 1))));

        var regions = AelisCyclicRegionDetector.detect(models);

        assertEquals(1, regions.size());
        var region = regions.getFirst();
        assertEquals(Set.of("meteorSeed", "pureMeteor", "meteorDust"), region.keys());
        assertEquals(2, region.variants().get("meteorSeed").size());
        assertTrue(region.variants().containsKey("pureMeteor"));
        assertTrue(region.variants().containsKey("meteorDust"));
    }

    @Test
    void rejectsOnlyTheCycleContainingAnUnsupportedProducer() {
        var models = new LinkedHashMap<String,
                AelisCyclicRegionDetector.KeyModel<String, String>>();
        models.put("A", model(true, variant("makeA", "A", 2, input("B", 1))));
        models.put("B", new AelisCyclicRegionDetector.KeyModel<>(
                List.of(variant("makeB", "B", 1, input("A", 1))),
                false, "ordered candidate contains a substitute input"));
        models.put("X", model(true, variant("growX", "X", 2, input("X", 1))));

        var analysis = AelisCyclicRegionDetector.analyze(models);
        var regions = analysis.regions();

        assertEquals(1, regions.size());
        assertEquals(Set.of("X"), regions.getFirst().keys());
        assertEquals(1, analysis.rejectedRegions().size());
        var rejected = analysis.rejectedRegions().getFirst();
        assertEquals(Set.of("A", "B"), rejected.keys());
        assertEquals(
                "ordered candidate contains a substitute input",
                rejected.reasons().get("B"));
    }

    @SafeVarargs
    private static AelisCyclicRegionDetector.KeyModel<String, String> model(
            boolean supported,
            AelisCyclicDemandSolver.Variant<String, String>... variants) {
        return new AelisCyclicRegionDetector.KeyModel<>(List.of(variants), supported);
    }

    @SafeVarargs
    private static AelisCyclicDemandSolver.Variant<String, String> variant(
            String id, String output, long outputAmount,
            AelisCyclicDemandSolver.Input<String>... inputs) {
        return new AelisCyclicDemandSolver.Variant<>(
                id, output, BigInteger.valueOf(outputAmount), List.of(inputs));
    }

    private static AelisCyclicDemandSolver.Input<String> input(String key, long amount) {
        return new AelisCyclicDemandSolver.Input<>(key, BigInteger.valueOf(amount));
    }
}
