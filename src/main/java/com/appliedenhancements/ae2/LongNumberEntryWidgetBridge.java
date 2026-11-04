package com.appliedenhancements.ae2;

import java.math.BigInteger;
import java.util.Optional;

/** Exact quantity access for AE2's number entry widget. */
public interface LongNumberEntryWidgetBridge {
    Optional<BigInteger> appliedenhancements$getExactValue();
    void appliedenhancements$setExactValue(BigInteger value);
    void appliedenhancements$enableExactInput();
}
