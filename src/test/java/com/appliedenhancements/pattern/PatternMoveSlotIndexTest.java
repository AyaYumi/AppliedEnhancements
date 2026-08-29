package com.appliedenhancements.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class PatternMoveSlotIndexTest {
    @Test
    void reservesIndexedEmptySlotsInTargetAndSlotOrder() {
        var first = new Target(true, false, true);
        var second = new Target(true, true);
        var targets = new LinkedHashMap<Long, Target>();
        targets.put(10L, first);
        targets.put(20L, second);
        var index = createIndex(targets);

        assertCandidate(index.findAndReserve("any", -1, Target::accepts), 10L, first, 0);
        assertCandidate(index.findAndReserve("any", -1, Target::accepts), 10L, first, 2);
        assertCandidate(index.findAndReserve("any", -1, Target::accepts), 20L, second, 0);
        assertCandidate(index.findAndReserve("any", -1, Target::accepts), 20L, second, 1);
        assertNull(index.findAndReserve("any", -1, Target::accepts));
    }

    @Test
    void rejectedSlotRemainsEligibleForAnotherPatternType() {
        var target = new Target(true, true);
        var targets = new LinkedHashMap<Long, Target>();
        targets.put(7L, target);
        var index = createIndex(targets);

        assertCandidate(index.findAndReserve("processing", -1,
                (candidate, slot, type) -> slot == 1), 7L, target, 1);
        assertCandidate(index.findAndReserve("crafting", -1,
                (candidate, slot, type) -> slot == 0), 7L, target, 0);
    }

    @Test
    void preferredSlotIsCheckedOnceThenFallsBack() {
        var target = new Target(true, true, true);
        var targets = new LinkedHashMap<Long, Target>();
        targets.put(3L, target);
        var index = createIndex(targets);
        int[] preferredChecks = { 0 };

        var result = index.findAndReserve("pattern", 1, (candidate, slot, type) -> {
            if (slot == 1) {
                preferredChecks[0]++;
                return false;
            }
            return slot == 2;
        });

        assertCandidate(result, 3L, target, 2);
        assertEquals(1, preferredChecks[0]);
    }

    @Test
    void preferredSlotIsIgnoredWhenMultipleTargetsExist() {
        var first = new Target(true, true);
        var second = new Target(true, true);
        var targets = new LinkedHashMap<Long, Target>();
        targets.put(1L, first);
        targets.put(2L, second);
        var index = createIndex(targets);

        assertCandidate(index.findAndReserve("any", 1, Target::accepts), 1L, first, 0);
    }

    @Test
    void acceptedBatchDoesNotRescanPreviouslyReservedSlots() {
        var target = new Target(new boolean[512]);
        java.util.Arrays.fill(target.empty, true);
        var targets = new LinkedHashMap<Long, Target>();
        targets.put(1L, target);
        var index = createIndex(targets);
        int[] acceptanceChecks = { 0 };

        for (int expectedSlot = 0; expectedSlot < target.empty.length; expectedSlot++) {
            var candidate = index.findAndReserve("any", -1, (ignored, slot, query) -> {
                acceptanceChecks[0]++;
                return true;
            });
            assertCandidate(candidate, 1L, target, expectedSlot);
        }

        assertEquals(target.empty.length, acceptanceChecks[0]);
    }

    private static PatternMoveSlotIndex<Target> createIndex(LinkedHashMap<Long, Target> targets) {
        return new PatternMoveSlotIndex<>(
                targets,
                target -> target.empty.length,
                (target, slot) -> target.empty[slot]);
    }

    private static void assertCandidate(
            PatternMoveSlotIndex.Candidate<Target> candidate,
            long targetId,
            Target target,
            int slot) {
        assertEquals(targetId, candidate.targetId());
        assertEquals(target, candidate.target());
        assertEquals(slot, candidate.slot());
    }

    private static final class Target {
        private final boolean[] empty;

        private Target(boolean... empty) {
            this.empty = empty;
        }

        private boolean accepts(int slot, String ignored) {
            return empty[slot];
        }
    }
}
