package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AelisByteAccountingTest {
    @Test
    void invariantReusableInputsAndReturnsChargeEveryPattern() {
        long leasedInputVolume = 2;
        long returnedVolume = 2;
        long patternTimes = 5;

        long additionalInputVolume =
                AelisByteAccounting.additionalRepeatedVolume(
                        leasedInputVolume, patternTimes);
        long additionalReturnVolume =
                AelisByteAccounting.additionalRepeatedVolume(
                        returnedVolume, patternTimes);

        assertEquals(10, leasedInputVolume + additionalInputVolume);
        assertEquals(10, returnedVolume + additionalReturnVolume);
    }

    @Test
    void finiteToolPoolChargesOneInputAndOneRemainderPerLogicalUse() {
        long logicalUses = AelisByteAccounting.totalLogicalVolume(1, 37);

        assertEquals(37, logicalUses);
        assertEquals(74, Math.addExact(logicalUses, logicalUses));
    }

    @Test
    void aSinglePatternNeedsNoRepeatedLeaseCharge() {
        assertEquals(
                0,
                AelisByteAccounting.additionalRepeatedVolume(4, 1));
    }

    @Test
    void byteVolumeOverflowIsDetectedBeforeCpuCapacityCanBeUnderestimated() {
        assertThrows(
                ArithmeticException.class,
                () -> AelisByteAccounting.totalLogicalVolume(
                        Long.MAX_VALUE, 2));
        assertThrows(
                ArithmeticException.class,
                () -> AelisByteAccounting.additionalRepeatedVolume(
                        Long.MAX_VALUE, 3));
    }
}
