package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AelisCycleSeedReservationTest {
    @Test
    void requestsEnoughExtraProductionToRestoreAConsumedSeed() {
        assertEquals(
                Map.of("A", BigInteger.ONE),
                AelisCycleSeedReservation.additions(
                        Map.of("A", 1L), Map.of(), Map.of()));
    }

    @Test
    void countsBothSolverSurplusAndAlreadyReservedDemand() {
        assertTrue(AelisCycleSeedReservation.additions(
                Map.of("A", 8L),
                Map.of("A", BigInteger.valueOf(16)),
                Map.of("A", BigInteger.valueOf(8))).isEmpty());
    }

    @Test
    void reservesTheCompleteFloorWhenOrdinarySurplusIsInsufficient() {
        assertEquals(
                Map.of("A", BigInteger.valueOf(5)),
                AelisCycleSeedReservation.additions(
                        Map.of("A", 8L),
                        Map.of("A", BigInteger.valueOf(2)),
                        Map.of("A", BigInteger.valueOf(3))));
    }

    @Test
    void reusesSufficientOrdinarySurplusWithoutArtificialReserveDemand() {
        assertTrue(AelisCycleSeedReservation.additions(
                Map.of("A", 8L),
                Map.of("A", BigInteger.valueOf(8)),
                Map.of()).isEmpty());
    }
}
