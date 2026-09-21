package com.github.appliedenhancements.integration.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.crafting.IPatternDetails;
import java.math.BigInteger;
import java.util.Map;

/** Exact crafted totals retained alongside AE2's long-only compatibility summary. */
public interface AelisBigIntegerCraftAmountsCarrier {
    default BigInteger appliedenhancements$getBigIntegerFinalAmount() { return null; }
    default void appliedenhancements$setBigIntegerFinalAmount(BigInteger amount) {
        if (amount != null) throw new UnsupportedOperationException("Exact final output is not supported");
    }
    /** Rounded-up storage estimate; null when a third-party plan supplies only its native byte field. */
    default BigInteger appliedenhancements$getBigIntegerBytes() { return null; }

    default void appliedenhancements$setBigIntegerBytes(BigInteger bytes) {
        if (bytes != null) throw new UnsupportedOperationException("Exact byte estimates are not supported");
    }

    Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts();

    void appliedenhancements$setBigIntegerCraftAmounts(Map<AEKey, BigInteger> amounts);

    /** Exact task counts for CPU integrations; patternTimes() remains a long projection. */
    default Map<IPatternDetails, BigInteger> appliedenhancements$getBigIntegerPatternTimes() {
        return Map.of();
    }

    default void appliedenhancements$setBigIntegerPatternTimes(Map<IPatternDetails, BigInteger> times) {
        if (!times.isEmpty()) throw new UnsupportedOperationException("Exact pattern counts are not supported");
    }

    default Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() {
        return Map.of();
    }

    /** Exact amount supplied from storage for this plan, not total network capacity. */
    default Map<AEKey, BigInteger> appliedenhancements$getBigIntegerStoredAmounts() {
        return Map.of();
    }

    default void appliedenhancements$setBigIntegerStoredAmounts(Map<AEKey, BigInteger> amounts) {
        if (!amounts.isEmpty()) throw new UnsupportedOperationException("Exact stored amounts are not supported");
    }

    /** Exact plan inputs supplied by explicitly marked infinite storage cells. */
    default Map<AEKey, BigInteger> appliedenhancements$getBigIntegerInfiniteAmounts() {
        return Map.of();
    }

    default void appliedenhancements$setBigIntegerInfiniteAmounts(Map<AEKey, BigInteger> amounts) {
        if (!amounts.isEmpty()) throw new UnsupportedOperationException("Exact infinite amounts are not supported");
    }

    /** Legacy name: marks a saturated long projection, never an execution veto. */
    default boolean appliedenhancements$isPreviewOnly() { return false; }

    default void appliedenhancements$setPreviewOnly(boolean previewOnly) {
        if (previewOnly) throw new UnsupportedOperationException("Projected plans are not supported");
    }

    default void appliedenhancements$setBigIntegerMissingAmounts(Map<AEKey, BigInteger> amounts) {
        if (!amounts.isEmpty()) throw new UnsupportedOperationException("Exact missing amounts are not supported");
    }
}
