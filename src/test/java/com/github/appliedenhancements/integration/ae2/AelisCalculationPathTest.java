package com.github.appliedenhancements.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AelisCalculationPathTest {
    @ParameterizedTest
    @EnumSource(AelisCalculationPath.class)
    void networkIdsRoundTrip(AelisCalculationPath path) {
        assertEquals(path, AelisCalculationPath.fromNetworkId(path.networkId()));
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, 1, 255, Integer.MAX_VALUE })
    void rejectsUnknownNetworkIds(int networkId) {
        assertThrows(
                IllegalArgumentException.class,
                () -> AelisCalculationPath.fromNetworkId(networkId));
    }
}
