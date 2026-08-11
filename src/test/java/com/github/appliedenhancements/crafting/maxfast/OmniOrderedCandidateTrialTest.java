package com.github.appliedenhancements.crafting.maxfast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OmniOrderedCandidateTrialTest {
    @Test
    void fastFirstCandidateSuccessExecutesExactlyOnce() throws Exception {
        var attempted = new ArrayList<Integer>();

        boolean applied = OmniOrderedCandidateTrial.tryInOrder(4, index -> {
            attempted.add(index);
            return true;
        });

        assertTrue(applied);
        assertEquals(List.of(0), attempted);
    }

    @Test
    void shortageAndFallbackDelegateNativeOnceWithoutStateLeak() throws Exception {
        for (var failure : List.of(TrialResult.SHORTAGE, TrialResult.FALLBACK)) {
            var state = new TransactionState();
            var nativeCalls = new AtomicInteger();

            boolean applied = OmniOrderedCandidateTrial.tryInOrder(
                    1, index -> state.tryCandidate(index, failure));
            if (!applied) {
                state.runNative(nativeCalls);
            }

            assertFalse(applied, failure.name());
            assertEquals(1, state.candidateAttempts, failure.name());
            assertEquals(1, nativeCalls.get(), failure.name());
            assertEquals(TransactionState.INITIAL_INVENTORY,
                    state.parentInventory, failure.name());
            assertEquals(TransactionState.INITIAL_MISSING,
                    state.parentMissing, failure.name());
            assertEquals(TransactionState.INITIAL_POSSIBLE,
                    state.possible, failure.name());
            assertTrue(state.nativeObservedPristineState, failure.name());
        }
    }

    @Test
    void appliedCandidateCommitsAllStagedStateAndSkipsNative() throws Exception {
        var state = new TransactionState();
        var nativeCalls = new AtomicInteger();

        boolean applied = OmniOrderedCandidateTrial.tryInOrder(
                1, index -> state.tryCandidate(index, TrialResult.APPLIED));
        if (!applied) {
            state.runNative(nativeCalls);
        }

        assertTrue(applied);
        assertEquals(1, state.candidateAttempts);
        assertEquals(0, nativeCalls.get());
        assertEquals(TransactionState.STAGED_INVENTORY, state.parentInventory);
        assertEquals(TransactionState.STAGED_MISSING, state.parentMissing);
        assertEquals(TransactionState.STAGED_POSSIBLE, state.possible);
    }

    @Test
    void deterministicCandidatePrefixKeepsExistingInOrderBehavior()
            throws Exception {
        var attempted = new ArrayList<Integer>();

        boolean applied = OmniOrderedCandidateTrial.tryInOrder(4, index -> {
            attempted.add(index);
            return index == 2;
        });

        assertTrue(applied);
        assertEquals(List.of(0, 1, 2), attempted);
    }

    @Test
    void exhaustedPrefixDelegatesOnlyAfterEveryAllowedCandidate()
            throws Exception {
        var attempted = new ArrayList<Integer>();
        var nativeCalls = new AtomicInteger();

        boolean applied = OmniOrderedCandidateTrial.tryInOrder(3, index -> {
            attempted.add(index);
            return false;
        });
        if (!applied) {
            nativeCalls.incrementAndGet();
        }

        assertFalse(applied);
        assertEquals(List.of(0, 1, 2), attempted);
        assertEquals(1, nativeCalls.get());
    }

    @Test
    void zeroLimitDelegatesWithoutSpeculativeCandidateExecution()
            throws Exception {
        var attempted = new AtomicInteger();

        boolean applied = OmniOrderedCandidateTrial.tryInOrder(
                0, index -> {
                    attempted.incrementAndGet();
                    return true;
                });

        assertFalse(applied);
        assertEquals(0, attempted.get());
    }

    @Test
    void interruptionStopsThePrefixAndPropagates() {
        var attempted = new AtomicInteger();

        assertThrows(InterruptedException.class,
                () -> OmniOrderedCandidateTrial.tryInOrder(3, index -> {
                    attempted.incrementAndGet();
                    throw new InterruptedException("cancelled");
                }));
        assertEquals(1, attempted.get());
    }

    private enum TrialResult {
        APPLIED,
        SHORTAGE,
        FALLBACK
    }

    /**
     * Pure model of the Planner's child inventory, staged missing counter and
     * possible-state snapshot. Failed attempts restore the snapshot and never
     * copy the staged values into the parent.
     */
    private static final class TransactionState {
        private static final long INITIAL_INVENTORY = 40;
        private static final long INITIAL_MISSING = 3;
        private static final boolean INITIAL_POSSIBLE = true;
        private static final long STAGED_INVENTORY = 31;
        private static final long STAGED_MISSING = 10;
        private static final boolean STAGED_POSSIBLE = false;

        private long parentInventory = INITIAL_INVENTORY;
        private long parentMissing = INITIAL_MISSING;
        private boolean possible = INITIAL_POSSIBLE;
        private int candidateAttempts;
        private boolean nativeObservedPristineState;

        boolean tryCandidate(int index, TrialResult result) {
            assertEquals(0, index);
            candidateAttempts++;

            long childInventory = STAGED_INVENTORY;
            long childMissing = STAGED_MISSING;
            boolean initialPossible = possible;
            boolean childPossible = STAGED_POSSIBLE;

            // Candidate traversal may mutate AE2's process flags. Its finally
            // block restores the pre-attempt snapshot before any outcome is
            // exposed to the caller.
            possible = childPossible;
            possible = initialPossible;

            if (result == TrialResult.APPLIED) {
                parentInventory = childInventory;
                parentMissing = childMissing;
                possible = childPossible;
                return true;
            }
            return false;
        }

        void runNative(AtomicInteger nativeCalls) {
            nativeCalls.incrementAndGet();
            nativeObservedPristineState = parentInventory == INITIAL_INVENTORY
                    && parentMissing == INITIAL_MISSING
                    && possible == INITIAL_POSSIBLE;
        }
    }
}
