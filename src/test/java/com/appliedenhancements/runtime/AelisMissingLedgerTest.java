package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.appliedenhancements.mixin.CraftingSimulationStateCyclicCraftingMixin;
import com.appliedenhancements.test.TestAEKey;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class AelisMissingLedgerTest {
    @Test void accumulationIsExactAndPreviouslyPublishedSnapshotsRemainImmutable() {
        var tracker = new CraftingSimulationStateCyclicCraftingMixin() {};
        var key = new TestAEKey("missing");
        BigInteger huge = BigInteger.ONE.shiftLeft(100);
        tracker.appliedenhancements$recordBigIntegerMissing(key, huge);
        var first = tracker.appliedenhancements$getBigIntegerMissingAmounts();
        tracker.appliedenhancements$recordBigIntegerMissing(key, BigInteger.ONE);
        for (int index = 0; index < 1000; index++) {
            tracker.appliedenhancements$recordBigIntegerMissing(new TestAEKey("missing_" + index), huge);
        }
        assertEquals(huge, first.get(key));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertEquals(huge.add(BigInteger.ONE), tracker.appliedenhancements$getBigIntegerMissingAmounts().get(key));
        assertEquals(1001, tracker.appliedenhancements$getBigIntegerMissingAmounts().size());
    }
}
