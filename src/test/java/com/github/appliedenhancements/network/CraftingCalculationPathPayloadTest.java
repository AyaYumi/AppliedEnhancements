package com.github.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CraftingCalculationPathPayloadTest {
    @Test
    void cyclicCraftAmountsArePositiveAndDefensivelyCopied() {
        var redstone = new TestAEKey("redstone");
        var source = new LinkedHashMap<appeng.api.stacks.AEKey, Long>();
        source.put(redstone, 42L);

        var payload = new CraftingCalculationPathPayload(
                7, AelisCalculationPath.AELIS, source);
        source.clear();

        assertEquals(Map.of(redstone, 42L), payload.cyclicCraftAmounts());
        assertThrows(UnsupportedOperationException.class,
                () -> payload.cyclicCraftAmounts().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new CraftingCalculationPathPayload(
                        7, AelisCalculationPath.AELIS, Map.of(redstone, 0L)));
    }
}
