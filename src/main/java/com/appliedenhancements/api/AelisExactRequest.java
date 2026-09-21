package com.appliedenhancements.api;

import java.math.BigInteger;
import java.util.Objects;

/** Exact final-output request. Native long arguments are compatibility projections only. */
public record AelisExactRequest(BigInteger amount) {
    public AelisExactRequest {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0 || amount.toString().length() > 256) {
            throw new IllegalArgumentException("Crafting request must be positive and at most 256 digits");
        }
    }
    public long projection() { return amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(); }
}
