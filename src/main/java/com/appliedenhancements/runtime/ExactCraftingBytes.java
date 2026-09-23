package com.appliedenhancements.runtime;

import appeng.api.stacks.AEKey;
import appeng.crafting.inv.CraftingSimulationState;
import com.github.appliedenhancements.integration.ae2.AelisCraftingBytesTracker;
import java.math.BigInteger;

/** Keep the original AE2 byte formula, without narrowing its operands. */
public final class ExactCraftingBytes {
    private ExactCraftingBytes() {}

    public static void addBytes(CraftingSimulationState state, long bytes) {
        addBytes(state, BigInteger.valueOf(bytes));
    }

    public static void addBytes(CraftingSimulationState state, BigInteger bytes) {
        add(state, new CraftingByteEstimate(bytes, BigInteger.ONE));
    }

    public static void addStackBytes(CraftingSimulationState state, AEKey key, BigInteger amount) {
        add(state, new CraftingByteEstimate(amount.multiply(BigInteger.valueOf(8)),
                BigInteger.valueOf(key.getType().getAmountPerByte())));
    }

    private static void add(CraftingSimulationState state, CraftingByteEstimate bytes) {
        if (state instanceof AelisCraftingBytesTracker tracker) {
            tracker.appliedenhancements$addExactBytes(bytes);
        } else {
            // Untransformed simulation states used by API consumers and unit fixtures.
            state.addBytes(bytes.projection());
        }
    }
}
