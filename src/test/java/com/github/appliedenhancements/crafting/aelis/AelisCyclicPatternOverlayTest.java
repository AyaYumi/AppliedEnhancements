package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AelisCyclicPatternOverlayTest {
    @Test
    void restoresOnlyRawCandidatesHiddenBehindRecursiveTerminalNodes() {
        var models = new LinkedHashMap<String,
                AelisCyclicRegionDetector.KeyModel<String, String>>();
        models.put("quartzDust", model(
                variant("pulverize", "quartzDust", 1,
                        input("pureMeteor", 1))));
        models.put("pureMeteor", model(
                variant("grow", "pureMeteor", 1,
                        input("meteorSeed", 1))));
        var queried = new ArrayList<String>();

        var overlay = AelisCyclicPatternOverlay.merge(
                models,
                List.of("meteorSeed", "diamond", "meteorSeed"),
                key -> {
                    queried.add(key);
                    return "meteorSeed".equals(key)
                            ? List.of(variant(
                                    "aggregator", "meteorSeed", 32,
                                    input("quartzDust", 8), input("diamond", 4)))
                            : List.of();
                });

        assertEquals(List.of("meteorSeed", "diamond"), queried);
        assertEquals(Set.of("meteorSeed"), overlay.restoredKeys());
        assertFalse(models.containsKey("meteorSeed"));
        var regions = AelisCyclicRegionDetector.detect(overlay.models());
        assertEquals(1, regions.size());
        assertEquals(
                Set.of("quartzDust", "pureMeteor", "meteorSeed"),
                regions.get(0).keys());
    }

    @Test
    void mergesRestoredCycleIntoOneGlobalDemandModel() {
        var base = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        base.put("quartzDust", List.of(
                variant("pulverize", "quartzDust", 1, input("pureMeteor", 1))));
        base.put("pureMeteor", List.of(
                variant("grow", "pureMeteor", 1, input("meteorSeed", 1))));
        var models = new LinkedHashMap<String,
                AelisCyclicRegionDetector.KeyModel<String, String>>();
        models.put("quartzDust", new AelisCyclicRegionDetector.KeyModel<>(
                base.get("quartzDust"), true));
        models.put("pureMeteor", new AelisCyclicRegionDetector.KeyModel<>(
                base.get("pureMeteor"), true));
        var overlay = AelisCyclicPatternOverlay.merge(
                models, List.of("meteorSeed"),
                key -> List.of(variant(
                        "aggregator", "meteorSeed", 32,
                        input("quartzDust", 8), input("diamond", 4))));
        var regions = AelisCyclicRegionDetector.detect(overlay.models());

        var global = AelisCyclicPatternOverlay.mergeIntoGlobal(base, regions);
        var solved = AelisCyclicDemandSolver.solve(
                new AelisCyclicDemandSolver.Problem<>(
                        global, Map.of("quartzDust", BigInteger.valueOf(80)),
                        Map.of("quartzDust", BigInteger.valueOf(8))),
                new AelisCyclicDemandSolver.Limits(
                        256, 1_000_000,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));

        assertEquals(Set.of("quartzDust", "pureMeteor", "meteorSeed"),
                global.keySet());
        assertEquals("pulverize", global.get("quartzDust").get(0).id());
        assertEquals("aggregator", global.get("meteorSeed").get(0).id());
        assertEquals(true, solved.solved(), () -> String.valueOf(solved.failure()));
        assertEquals(BigInteger.valueOf(3),
                solved.plan().firings().get("aggregator"));
        assertEquals(BigInteger.valueOf(12), solved.plan().missing().get("diamond"));
        assertFalse(solved.plan().missing().containsKey("meteorSeed"));
    }

    private static AelisCyclicRegionDetector.KeyModel<String, String> model(
            AelisCyclicDemandSolver.Variant<String, String> variant) {
        return new AelisCyclicRegionDetector.KeyModel<>(List.of(variant), true);
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
