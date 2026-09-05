package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AelisCyclicDemandSolverTest {
    @Test
    void solvesNestedSharedSelfGrowthInCondensedDagOrder() {
        var variants = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        variants.put("F", List.of(variant(
                "F", "F", 9,
                input("F", 1), input("A", 1), input("D", 1), input("E", 1))));
        variants.put("E", List.of(variant(
                "E", "E", 9, input("E", 1), input("D", 1))));
        variants.put("D", List.of(variant(
                "D", "D", 9, input("D", 1), input("A", 1))));
        variants.put("A", List.of(variant(
                "A", "A", 9,
                input("A", 1), input("B", 1), input("C", 1))));

        var result = solve(
                variants,
                Map.of("F", bi(100)),
                Map.of("F", bi(1), "E", bi(1), "D", bi(1), "A", bi(1)),
                limits(256, 1_000_000));

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(bi(13), result.plan().firings().get("F"));
        assertEquals(bi(2), result.plan().firings().get("E"));
        assertEquals(bi(2), result.plan().firings().get("D"));
        assertEquals(bi(2), result.plan().firings().get("A"));
        assertEquals(bi(2), result.plan().missing().get("B"));
        assertEquals(bi(2), result.plan().missing().get("C"));
        assertFalse(result.plan().missing().containsKey("A"));
        assertFalse(result.plan().missing().containsKey("D"));
        assertFalse(result.plan().missing().containsKey("E"));
        assertFalse(result.plan().missing().containsKey("F"));
        assertEquals(bi(1), result.plan().requiredAvailable().get("A"));
        assertEquals(bi(1), result.plan().requiredAvailable().get("D"));
        assertEquals(bi(1), result.plan().requiredAvailable().get("E"));
        assertEquals(bi(1), result.plan().requiredAvailable().get("F"));
        assertEquals(
                List.of("A", "D", "E", "F"),
                result.plan().executionSchedule().stream()
                        .map(AelisCyclicDemandSolver.ScheduleStep::id)
                        .toList());
    }

    @Test
    void solvesThreeNodeProductiveCycleWithOneSeed() {
        var variants = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        variants.put("A", List.of(variant("makeA", "A", 2, input("D", 1))));
        variants.put("D", List.of(variant("makeD", "D", 2, input("E", 1))));
        variants.put("E", List.of(variant("makeE", "E", 2, input("A", 1))));

        var result = solve(
                variants,
                Map.of("A", bi(8)),
                Map.of("A", bi(1)),
                limits(256, 1_000_000));

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(bi(4), result.plan().firings().get("makeA"));
        assertEquals(bi(2), result.plan().firings().get("makeD"));
        assertEquals(bi(1), result.plan().firings().get("makeE"));
        assertTrue(result.plan().missing().isEmpty());
        assertEquals(Map.of("A", bi(1)), result.plan().requiredAvailable());
        assertEquals(
                List.of("makeE", "makeD", "makeA"),
                result.plan().executionSchedule().stream()
                        .map(AelisCyclicDemandSolver.ScheduleStep::id)
                        .toList());
        assertEquals(
                List.of(bi(1), bi(2), bi(4)),
                result.plan().executionSchedule().stream()
                        .map(AelisCyclicDemandSolver.ScheduleStep::firings)
                        .toList());
    }

    @Test
    void reportsOnlyOneStartupSeedWhenCycleHasNoStock() {
        var variants = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        variants.put("A", List.of(variant("makeA", "A", 2, input("D", 1))));
        variants.put("D", List.of(variant("makeD", "D", 2, input("E", 1))));
        variants.put("E", List.of(variant("makeE", "E", 2, input("A", 1))));

        var result = solve(
                variants, Map.of("A", bi(8)), Map.of(),
                limits(256, 1_000_000));

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(bi(1), result.plan().missing().values().stream()
                .reduce(BigInteger.ZERO, BigInteger::add));
        assertTrue(result.plan().requiredAvailable().isEmpty());
    }

    @Test
    void solvesLargeNearNeutralSelfFeedbackWithoutLinearIteration() {
        var variants = Map.of(
                "A", List.of(variant(
                        "growA", "A", 1_000_001, input("A", 1_000_000))));

        var result = solve(
                variants, Map.of("A", bi(8_000_000)), Map.of("A", bi(1_000_000)),
                limits(256, 10_000));

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(bi(7_000_000), result.plan().firings().get("growA"));
        assertEquals(bi(1_000_000), result.plan().requiredAvailable().get("A"));
    }

    @Test
    void triesCandidatesLazilyInOrderAndSkipsNonGrowingChoice() {
        var variants = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        variants.put("A", List.of(
                variant("noGrowth", "A", 1, input("A", 1)),
                variant("growth", "A", 2, input("A", 1))));

        var result = solve(
                variants, Map.of("A", bi(10)), Map.of("A", bi(1)),
                limits(256, 10_000));

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertFalse(result.plan().firings().containsKey("noGrowth"));
        assertEquals(bi(9), result.plan().firings().get("growth"));
    }

    @Test
    void handlesDeepNestingWithoutCallStackRecursion() {
        int depth = 10_000;
        var variants = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        for (int index = 0; index < depth - 1; index++) {
            String key = "K" + index;
            variants.put(key, List.of(variant(
                    "make" + key, key, 1, input("K" + (index + 1), 1))));
        }
        String last = "K" + (depth - 1);
        variants.put(last, List.of(variant(
                "growLast", last, 2, input(last, 1))));

        var result = solve(
                variants, Map.of("K0", bi(1)), Map.of(last, bi(1)),
                limits(256, 1_000_000));

        assertTrue(result.solved(), () -> String.valueOf(result.failure()));
        assertEquals(bi(1), result.plan().firings().get("makeK0"));
        assertTrue(result.plan().missing().isEmpty());
    }

    @Test
    void enforcesSccAndSearchStateLimits() {
        var ring = new LinkedHashMap<String,
                List<AelisCyclicDemandSolver.Variant<String, String>>>();
        ring.put("A", List.of(variant("A", "A", 2, input("D", 1))));
        ring.put("D", List.of(variant("D", "D", 2, input("E", 1))));
        ring.put("E", List.of(variant("E", "E", 2, input("A", 1))));
        var tooLarge = solve(
                ring, Map.of("A", bi(8)), Map.of("A", bi(1)),
                limits(2, 10_000));
        assertEquals(
                AelisCyclicDemandSolver.Failure.SCC_NODE_LIMIT,
                tooLarge.failure());

        var noGrowth = Map.of(
                "A", List.of(variant("A", "A", 1, input("A", 1))));
        var impossible = solve(
                noGrowth, Map.of("A", bi(8)), Map.of("A", bi(1)),
                limits(256, 10));
        assertEquals(
                AelisCyclicDemandSolver.Failure.NO_INTEGER_SOLUTION,
                impossible.failure());

        var manyCandidates = Map.of(
                "A", java.util.stream.IntStream.range(0, 20)
                        .mapToObj(index -> variant(
                                "A" + index, "A", 1, input("A", 1)))
                        .toList());
        var stateLimited = solve(
                manyCandidates, Map.of("A", bi(8)), Map.of("A", bi(1)),
                limits(256, 10));
        assertEquals(
                AelisCyclicDemandSolver.Failure.SEARCH_STATE_LIMIT,
                stateLimited.failure());
    }

    private static AelisCyclicDemandSolver.Result<String, String> solve(
            Map<String, List<AelisCyclicDemandSolver.Variant<String, String>>> variants,
            Map<String, BigInteger> demands,
            Map<String, BigInteger> available,
            AelisCyclicDemandSolver.Limits limits) {
        return AelisCyclicDemandSolver.solve(
                new AelisCyclicDemandSolver.Problem<>(
                        variants, demands, available),
                limits);
    }

    private static AelisCyclicDemandSolver.Limits limits(
            int maxNodes, long maxStates) {
        return new AelisCyclicDemandSolver.Limits(
                maxNodes, maxStates,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    @SafeVarargs
    private static AelisCyclicDemandSolver.Variant<String, String> variant(
            String id, String output, long amount,
            AelisCyclicDemandSolver.Input<String>... inputs) {
        return new AelisCyclicDemandSolver.Variant<>(
                id, output, bi(amount), List.of(inputs));
    }

    private static AelisCyclicDemandSolver.Input<String> input(
            String key, long amount) {
        return new AelisCyclicDemandSolver.Input<>(key, bi(amount));
    }

    private static BigInteger bi(long value) {
        return BigInteger.valueOf(value);
    }
}
