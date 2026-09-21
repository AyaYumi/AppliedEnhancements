package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import com.appliedenhancements.test.TestAEKey;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactCraftingStatusTest {
    @Test void countsOutputQuantityNotWindowsAndMergesSharedOutputs() {
        var key = new TestAEKey("output");
        var secondary = new TestAEKey("secondary");
        var first = pattern(List.of(new GenericStack(key, 4), new GenericStack(secondary, 2)));
        var second = pattern(List.of(new GenericStack(key, 3)));
        var completed = pattern(List.of(new GenericStack(key, 100)));
        var amount = new BigInteger("99999999999999999999");
        var counts = Map.of(first, amount, second, BigInteger.valueOf(7), completed, BigInteger.ZERO);
        var pending = ExactCraftingStatus.pending(counts.keySet(), counts::get);
        assertEquals(amount.multiply(BigInteger.valueOf(4)).add(BigInteger.valueOf(21)), pending.get(key));
        assertEquals(amount.multiply(BigInteger.TWO), pending.get(secondary));
    }
    private static IPatternDetails pattern(List<GenericStack> outputs) {
        return new IPatternDetails() {
            public appeng.api.stacks.AEItemKey getDefinition() { return null; }
            public IInput[] getInputs() { return new IInput[0]; }
            public List<GenericStack> getOutputs() { return outputs; }
        };
    }
}
