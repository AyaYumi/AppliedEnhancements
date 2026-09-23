package com.appliedenhancements.api;

import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

/** One accepted push: output for this row and actual scaled input totals. */
public record AelisCraftingBatch(BigInteger outputAmount, Map<AEKey, BigInteger> inputs) {
    public AelisCraftingBatch {
        Objects.requireNonNull(outputAmount);
        if (outputAmount.signum() <= 0) throw new IllegalArgumentException("Invalid batch output");
        inputs = Map.copyOf(inputs);
        if (inputs.size() > 64 || inputs.values().stream().anyMatch(n -> n.signum() <= 0)) {
            throw new IllegalArgumentException("Invalid batch inputs");
        }
    }
}
