package com.appliedenhancements.ae2;

import java.math.BigInteger;
import appeng.api.stacks.AEKey;

/** Menu transport, independent of any particular CPU implementation. */
public interface ExactCraftingMenuBridge {
    default void appliedenhancements$confirmExact(BigInteger amount, boolean missing, boolean autoStart) {}
    default boolean appliedenhancements$planExact(AEKey key, BigInteger amount) { return false; }
    default BigInteger appliedenhancements$getInitialExactAmount() { return null; }
    default void appliedenhancements$setInitialExactAmount(BigInteger amount) {}
}
