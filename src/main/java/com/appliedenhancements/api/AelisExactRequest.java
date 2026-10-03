package com.appliedenhancements.api;

import java.math.BigInteger;
import java.util.Objects;

/** Exact final-output request. Native long arguments are compatibility projections only. */
public record AelisExactRequest(BigInteger amount) {
    public AelisExactRequest {
        amount = Objects.requireNonNull(amount, "amount").max(BigInteger.ZERO);
    }
    public long projection() { return amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(); }
    public AelisExactRequest attempt(long amount, long originalProjection) {
        return amount == originalProjection ? this : new AelisExactRequest(BigInteger.valueOf(amount));
    }
}
