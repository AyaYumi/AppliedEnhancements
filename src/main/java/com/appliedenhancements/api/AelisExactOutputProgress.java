package com.appliedenhancements.api;

import java.math.BigInteger;
import java.util.Objects;

/** CPU-independent exact delivery ledger. Persist remaining(), not its long display window. */
public final class AelisExactOutputProgress {
    private BigInteger remaining;
    public AelisExactOutputProgress(BigInteger remaining) {
        this.remaining = Objects.requireNonNull(remaining);
        if (remaining.signum() < 0) throw new IllegalArgumentException("Negative remaining output");
    }
    public BigInteger remaining() { return remaining; }
    public long window() { return remaining.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(); }
    public boolean complete() { return remaining.signum() == 0; }
    /** Call only for actually accepted MODULATE output, never a simulated insertion. */
    public long delivered(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative delivery");
        remaining = remaining.subtract(BigInteger.valueOf(amount)).max(BigInteger.ZERO);
        return window();
    }
}
