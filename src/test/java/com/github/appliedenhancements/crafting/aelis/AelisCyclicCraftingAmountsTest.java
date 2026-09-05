package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import appeng.api.stacks.GenericStack;
import com.appliedenhancements.test.TestAEKey;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class AelisCyclicCraftingAmountsTest {
    @Test
    void sumsProducedItemAmountsAcrossCyclicPatternFirings() {
        var redstone = new TestAEKey("redstone");
        var quartz = new TestAEKey("quartz");
        var totals = new LinkedHashMap<appeng.api.stacks.AEKey, Long>();

        AelisCyclicCraftingAmounts.addOutputs(
                totals,
                List.of(new GenericStack(redstone, 2), new GenericStack(quartz, 3)),
                5);
        AelisCyclicCraftingAmounts.addOutputs(
                totals, List.of(new GenericStack(redstone, 4)), 2);

        assertEquals(18L, totals.get(redstone));
        assertEquals(15L, totals.get(quartz));
    }

    @Test
    void saturatesDisplayTotalsInsteadOfOverflowing() {
        var redstone = new TestAEKey("redstone");
        var totals = new LinkedHashMap<appeng.api.stacks.AEKey, Long>();

        AelisCyclicCraftingAmounts.addOutputs(
                totals,
                List.of(new GenericStack(redstone, Long.MAX_VALUE)),
                2);

        assertEquals(Long.MAX_VALUE, totals.get(redstone));
    }
}
