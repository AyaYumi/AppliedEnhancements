package com.github.appliedenhancements.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.appliedenhancements.network.CraftingCalculationPathPayload;
import com.github.appliedenhancements.network.CraftingCalculationProgressPayload;
import org.junit.jupiter.api.Test;

class AelisInternalProtocolTest {
    @Test
    void aelisPathUsesTheNewProtocolIdentity() {
        AelisCalculationPath aelis = Enum.valueOf(AelisCalculationPath.class, "AELIS");

        assertEquals(4, aelis.networkId());
        assertEquals(aelis, AelisCalculationPath.fromNetworkId(4));
        assertThrows(IllegalArgumentException.class,
                () -> AelisCalculationPath.fromNetworkId(1));
        assertEquals("appliedenhancements:aelis_calculation_path",
                CraftingCalculationPathPayload.TYPE.id().toString());
    }

    @Test
    void aelisProgressPhasesUseTheNewProtocolIdentity() {
        CraftingCalculationProgressPhase compiling = Enum.valueOf(
                CraftingCalculationProgressPhase.class, "AELIS_COMPILING");
        CraftingCalculationProgressPhase executing = Enum.valueOf(
                CraftingCalculationProgressPhase.class, "AELIS_EXECUTING");

        assertEquals(11, compiling.networkId());
        assertEquals(12, executing.networkId());
        assertEquals(compiling, CraftingCalculationProgressPhase.fromNetworkId(11));
        assertEquals(executing, CraftingCalculationProgressPhase.fromNetworkId(12));
        assertThrows(IllegalArgumentException.class,
                () -> CraftingCalculationProgressPhase.fromNetworkId(4));
        assertThrows(IllegalArgumentException.class,
                () -> CraftingCalculationProgressPhase.fromNetworkId(5));
        assertEquals("appliedenhancements:aelis_calculation_progress",
                CraftingCalculationProgressPayload.TYPE.id().toString());
    }
}
