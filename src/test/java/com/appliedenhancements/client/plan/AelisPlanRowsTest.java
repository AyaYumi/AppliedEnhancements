package com.appliedenhancements.client.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AelisPlanRowsTest {
    private static AelisPlanRows.Row<String> row(String name, long stored, long craft, long missing, long cyclic) {
        return new AelisPlanRows.Row<>(name, name, "test:" + name, BigInteger.valueOf(stored),
                BigInteger.valueOf(craft), BigInteger.valueOf(missing), BigInteger.valueOf(cyclic));
    }

    @Test
    void exactAmountsOverrideEvenWhenZeroAndDoNotOverflow() {
        var huge = BigInteger.TEN.pow(40);
        assertEquals(huge, AelisPlanRows.exact(Map.of("a", huge), "a", Long.MAX_VALUE));
        assertEquals(BigInteger.ZERO, AelisPlanRows.exact(Map.of("a", BigInteger.ZERO), "a", 100));
        assertEquals(BigInteger.valueOf(12), AelisPlanRows.exact(Map.of(), "a", 12));
        var row = new AelisPlanRows.Row<>("a", "a", "test:a", huge, huge, huge, huge);
        assertEquals(huge.multiply(BigInteger.valueOf(3)), row.demand(), "cyclic is a breakdown, not extra demand");
    }

    @Test
    void filtersSearchAndMissingPriorityUseActualRows() {
        var normal = row("Iron", 10, 0, 0, 0);
        var cyclic = row("Processor", 2, 6, 0, 4);
        var missing = row("Quartz", 0, 0, 8, 0);
        var all = List.of(normal, cyclic, missing);
        assertEquals(missing, AelisPlanRows.filter(all, AelisPlanRows.Filter.ALL, "").getFirst());
        assertEquals(List.of(cyclic), AelisPlanRows.filter(all, AelisPlanRows.Filter.CYCLIC, " PROc "));
        assertEquals(List.of(cyclic), AelisPlanRows.filter(all, AelisPlanRows.Filter.CRAFT, "test:"));
        assertEquals(List.of(missing), AelisPlanRows.filter(all, AelisPlanRows.Filter.MISSING, ""));
        assertTrue(AelisPlanRows.filter(all, AelisPlanRows.Filter.ALL, "not found").isEmpty());
    }

    @Test
    void allInputsToStartGateAreRequired() {
        for (boolean plan : List.of(false, true)) {
            for (boolean simulation : List.of(false, true)) {
                for (boolean noCpu : List.of(false, true)) {
                    assertEquals(plan && !simulation && !noCpu,
                            AelisPlanRows.canStart(plan, simulation, noCpu));
                }
            }
        }
    }

    @Test
    void scrollingClampsAfterFilteringOrResize() {
        assertEquals(0, AelisPlanRows.clampScroll(42, 0, 8));
        assertEquals(0, AelisPlanRows.clampScroll(-5, 50, 8));
        assertEquals(42, AelisPlanRows.clampScroll(100, 50, 8));
        assertEquals(0, AelisPlanRows.clampScroll(4, 3, 8));
    }

    @Test
    void cyclicColumnUsesActualPositiveCycleAmountsIncludingExactOverflow() {
        assertFalse(AelisPlanRows.hasCyclicAmounts(List.of()));
        assertFalse(AelisPlanRows.hasCyclicAmounts(List.of(row("ordinary", 0, 999, 0, 0))));
        assertFalse(AelisPlanRows.hasCyclicAmounts(List.of(row("invalid", 0, 0, 0, -1))));
        var huge = BigInteger.TEN.pow(40);
        var cyclic = new AelisPlanRows.Row<>("cycle", "cycle", "test:cycle", BigInteger.ZERO,
                huge, BigInteger.ZERO, huge);
        assertTrue(AelisPlanRows.hasCyclicAmounts(List.of(cyclic)));
        assertEquals(huge, cyclic.demand(), "The new column is a breakdown, never additional demand");
    }

    @Test
    void hidingCyclicRowsMustNotChangeTheCompletePlansColumns() {
        var all = List.of(row("Iron", 2, 0, 0, 0), row("Processor", 0, 6, 0, 4));
        assertFalse(AelisPlanRows.hasCyclicAmounts(AelisPlanRows.filter(all, AelisPlanRows.Filter.ALL, "Iron")));
        assertTrue(AelisPlanRows.hasCyclicAmounts(all));
        assertTrue(AelisPlanRows.filter(all, AelisPlanRows.Filter.MISSING, "").isEmpty());
        assertTrue(AelisPlanRows.hasCyclicAmounts(all));
    }
}
