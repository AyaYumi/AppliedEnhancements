package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.test.TestAEKey;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class AelisSmartCycleBigIntegerTest {
    private static final TestAEKey INPUT = new TestAEKey("input");
    private static final TestAEKey OUTPUT = new TestAEKey("output");

    @Test
    void usefulTierThreadsMultiplyTheLongMaterialWindow() {
        var holders = new KeyCounter[9];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = new KeyCounter();
            holders[i].add(INPUT, 1);
        }
        var window = BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(11));

        assertEquals(window,
                AelisSmartCycleBatchProvider.maximumWindowedCrafts(
                        pattern(1), holders, window, 11));
    }

    @Test
    void outputSegmentsUseTheSameThreadScaledWindow() {
        var holder = new KeyCounter();
        holder.add(INPUT, 1);
        var window = BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(11));

        assertEquals(window.divide(BigInteger.valueOf(4)),
                AelisSmartCycleBatchProvider.maximumWindowedCrafts(
                        pattern(4), new KeyCounter[] {holder},
                        window, 11));
        assertEquals(BigInteger.ZERO,
                AelisSmartCycleBatchProvider.maximumWindowedCrafts(
                        pattern(1), new KeyCounter[] {holder},
                        window, 0));
    }

    @Test
    void physicalWindowFollowsMachineThreadSegmentation() {
        var holder = new KeyCounter();
        holder.add(INPUT, 1);
        var threads = 111_111_111;
        var requested = BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(threads));

        assertEquals(requested,
                AelisSmartCycleBatchProvider.maximumWindowedCrafts(
                        pattern(1), new KeyCounter[] {holder}, requested, threads));
    }

    @Test
    void adaptiveWindowStartsAtOnePhysicalSegment() {
        var holder = new KeyCounter();
        holder.add(INPUT, 1);
        var physicalSegment = BigInteger.valueOf(Long.MAX_VALUE);

        assertEquals(physicalSegment.divide(BigInteger.valueOf(4)),
                AelisSmartCycleBatchProvider.maximumWindowedCrafts(
                        pattern(4), new KeyCounter[] {holder},
                        physicalSegment, 11, 11, null, 1L,
                        BigInteger.ONE));
    }

    @Test
    void operationsPerPushAreIncludedInPhysicalSegmentLimit() {
        var holder = new KeyCounter();
        holder.add(INPUT, 1);
        var physicalSegment = BigInteger.valueOf(Long.MAX_VALUE);

        assertEquals(physicalSegment.divide(BigInteger.valueOf(8)),
                AelisSmartCycleBatchProvider.maximumWindowedCrafts(
                        pattern(4), new KeyCounter[] {holder},
                        physicalSegment, 11, 11, null, 2L,
                        BigInteger.ONE));
    }

    @Test
    void adaptiveControllerDoublesFastBatchesAndShrinksSlowBatches() {
        assertEquals(BigInteger.TWO,
                AelisSmartCycleBatchProvider.AdaptiveSegmentController
                        .nextTarget(BigInteger.ONE, 1_000_000L));
        assertEquals(BigInteger.ONE,
                AelisSmartCycleBatchProvider.AdaptiveSegmentController
                        .nextTarget(BigInteger.valueOf(16), 80_000_000L));
    }

    private static IPatternDetails pattern(long outputAmount) {
        return new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return null;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(OUTPUT, outputAmount));
            }
        };
    }
}
