package com.github.appliedenhancements.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class CraftingCalculationPathPayloadTest {
    @Test void exactBytesRetainPrecisionAndShareThePayloadBudget() {
        var bytes = BigInteger.TEN.pow(24).add(BigInteger.ONE);
        assertEquals(bytes, new CraftingCalculationPathPayload(7, AelisCalculationPath.AELIS,
                Map.of(), Map.of(), Map.of(), Map.of(), bytes).bigIntegerBytes());
        assertThrows(IllegalArgumentException.class, () -> new CraftingCalculationPathPayload(7,
                AelisCalculationPath.AELIS, Map.of(), Map.of(), Map.of(), Map.of(), BigInteger.ONE.negate()));
        var huge = BigInteger.ONE.shiftLeft(65_535 * 8);
        var entries = Map.<appeng.api.stacks.AEKey, BigInteger>of(new TestAEKey("a"), huge, new TestAEKey("b"), huge);
        assertThrows(IllegalArgumentException.class, () -> new CraftingCalculationPathPayload(7,
                AelisCalculationPath.AELIS, Map.of(), entries, entries, Map.of(), BigInteger.ONE));
    }

    @Test void storedAmountsAreExactImmutableAndShareAllThreeBudgets() {
        var key = new TestAEKey("infinite_essence");
        var source = new LinkedHashMap<appeng.api.stacks.AEKey, BigInteger>();
        var amount = new BigInteger("9216000000000000000000");
        source.put(key, amount);
        var payload = new CraftingCalculationPathPayload(7, AelisCalculationPath.AELIS,
                Map.of(), Map.of(), Map.of(), source);
        source.clear();
        assertEquals(amount, payload.bigIntegerStoredAmounts().get(key));
        assertThrows(UnsupportedOperationException.class, () -> payload.bigIntegerStoredAmounts().clear());
        assertThrows(IllegalArgumentException.class, () -> new CraftingCalculationPathPayload(
                7, AelisCalculationPath.AELIS, Map.of(), Map.of(), Map.of(), Map.of(key, BigInteger.ZERO)));
        var huge = BigInteger.ONE.shiftLeft(65_535 * 8);
        var twoEntries = Map.<appeng.api.stacks.AEKey, BigInteger>of(key, huge, new TestAEKey("other"), huge);
        assertThrows(IllegalArgumentException.class, () -> new CraftingCalculationPathPayload(
                7, AelisCalculationPath.AELIS, Map.of(), twoEntries, twoEntries, Map.of(key, huge)));
    }
    @Test
    void cyclicCraftAmountsArePositiveAndDefensivelyCopied() {
        var redstone = new TestAEKey("redstone");
        var source = new LinkedHashMap<appeng.api.stacks.AEKey, Long>();
        source.put(redstone, 42L);

        var payload = new CraftingCalculationPathPayload(
                7, AelisCalculationPath.AELIS, source,
                Map.of(redstone, BigInteger.TEN.pow(21)));
        source.clear();

        assertEquals(Map.of(redstone, 42L), payload.cyclicCraftAmounts());
        assertThrows(UnsupportedOperationException.class,
                () -> payload.cyclicCraftAmounts().clear());
        assertEquals(BigInteger.TEN.pow(21),
                payload.bigIntegerCraftAmounts().get(redstone));
        assertThrows(IllegalArgumentException.class,
                () -> new CraftingCalculationPathPayload(
                        7, AelisCalculationPath.AELIS,
                        Map.of(redstone, 0L), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CraftingCalculationPathPayload(
                        7, AelisCalculationPath.AELIS,
                        Map.of(), Map.of(redstone,
                                BigInteger.ONE.shiftLeft(65_536 * 8))));
    }

    @Test void missingAmountsAreExactImmutableAndShareThePayloadBudget() {
        var key = new TestAEKey("raw_essence");
        var source = new LinkedHashMap<appeng.api.stacks.AEKey, BigInteger>();
        source.put(key, BigInteger.TEN.pow(21));
        var payload = new CraftingCalculationPathPayload(7, AelisCalculationPath.AELIS, Map.of(), Map.of(), source);
        source.clear();
        assertEquals(BigInteger.TEN.pow(21), payload.bigIntegerMissingAmounts().get(key));
        assertThrows(UnsupportedOperationException.class, () -> payload.bigIntegerMissingAmounts().clear());
        assertThrows(IllegalArgumentException.class, () -> new CraftingCalculationPathPayload(
                7, AelisCalculationPath.AELIS, Map.of(), Map.of(), Map.of(key, BigInteger.valueOf(-1))));
    }
}
