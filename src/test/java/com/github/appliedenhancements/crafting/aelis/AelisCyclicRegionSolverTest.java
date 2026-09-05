package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AelisCyclicRegionSolverTest {
    @Test
    void solvesMeteorSeedGrowthLoopAndSeparatesExternalInputs() {
        var variants = meteorVariants();
        var region = new AelisCyclicRegionDetector.Region<>(
                Set.of("meteorSeed", "pureMeteor", "meteorDust"), variants);

        var result = AelisCyclicRegionSolver.solve(
                region,
                "meteorDust", bi(80),
                Map.of("meteorDust", bi(8)),
                limits());

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(bi(3), result.plan().firings().get("aggregator"));
        assertEquals(bi(96), result.plan().firings().get("grow"));
        assertEquals(bi(96), result.plan().firings().get("pulverize"));
        assertEquals(Map.of("certusDust", bi(24), "gravel", bi(48)),
                result.plan().externalDemands());
        assertTrue(result.plan().missingSeeds().isEmpty());
        assertEquals(Map.of("meteorDust", bi(8)),
                result.plan().requiredAvailable());
    }

    @Test
    void reportsOnlyInternalStartupInventoryWhenTheRegionHasNoStock() {
        var variants = meteorVariants();
        var region = new AelisCyclicRegionDetector.Region<>(
                Set.of("meteorSeed", "pureMeteor", "meteorDust"), variants);

        var result = AelisCyclicRegionSolver.solve(
                region,
                "meteorDust", bi(80), Map.of(), limits());

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(bi(8), result.plan().missingSeeds().values().stream()
                .reduce(BigInteger.ZERO, BigInteger::add));
        assertTrue(result.plan().missingSeeds().keySet().stream()
                .allMatch(region.keys()::contains));
        assertEquals(Map.of("certusDust", bi(32), "gravel", bi(64)),
                result.plan().externalDemands());
    }

    @Test
    void extraDemandOverproducesEnoughToReturnTheStartupSeedAfterTheOrder() {
        var variants = meteorVariants();
        var region = new AelisCyclicRegionDetector.Region<>(
                Set.of("meteorSeed", "pureMeteor", "meteorDust"), variants);

        var result = AelisCyclicRegionSolver.solve(
                region,
                "meteorDust", bi(80),
                Map.of("meteorDust", bi(8)),
                Map.of("meteorDust", bi(8)),
                limits());

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(Map.of("meteorDust", bi(8)),
                result.plan().requiredAvailable());
        BigInteger returnedAfterOriginalOrder =
                result.plan().surplus().getOrDefault("meteorDust", BigInteger.ZERO)
                        .add(bi(8));
        assertTrue(returnedAfterOriginalOrder.compareTo(bi(8)) >= 0);
        assertEquals(bi(4), result.plan().firings().get("aggregator"));
    }

    private static Map<String, List<
            AelisCyclicDemandSolver.Variant<String, String>>> meteorVariants() {
        var variants = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        variants.put("meteorSeed", List.of(
                variant("craftingTable", "meteorSeed", 1,
                        input("meteorDust", 1), input("certusDust", 1),
                        input("gravel", 2)),
                variant("aggregator", "meteorSeed", 32,
                        input("meteorDust", 8), input("certusDust", 8),
                        input("gravel", 16))));
        variants.put("pureMeteor", List.of(
                variant("grow", "pureMeteor", 1, input("meteorSeed", 1))));
        variants.put("meteorDust", List.of(
                variant("pulverize", "meteorDust", 1, input("pureMeteor", 1))));
        return variants;
    }

    private static AelisCyclicDemandSolver.Limits limits() {
        return new AelisCyclicDemandSolver.Limits(
                256, 1_000_000,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    @SafeVarargs
    private static AelisCyclicDemandSolver.Variant<String, String> variant(
            String id, String output, long outputAmount,
            AelisCyclicDemandSolver.Input<String>... inputs) {
        return new AelisCyclicDemandSolver.Variant<>(
                id, output, bi(outputAmount), List.of(inputs));
    }

    private static AelisCyclicDemandSolver.Input<String> input(String key, long amount) {
        return new AelisCyclicDemandSolver.Input<>(key, bi(amount));
    }

    private static BigInteger bi(long value) {
        return BigInteger.valueOf(value);
    }
}
