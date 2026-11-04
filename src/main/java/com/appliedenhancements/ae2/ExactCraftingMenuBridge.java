package com.appliedenhancements.ae2;

import java.math.BigInteger;

/** Quantity input and transport only; planning stays on the host menu. */
public interface ExactCraftingMenuBridge {
    BigInteger appliedenhancements$getInitialExactAmount();
    void appliedenhancements$setInitialExactAmount(BigInteger amount);
    void appliedenhancements$confirmExact(BigInteger amount, boolean missing, boolean autoStart);
}
