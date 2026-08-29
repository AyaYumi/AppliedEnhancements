package com.appliedenhancements.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StorageBusSlotIndexTest {
    @Test
    void publishesCandidateSlotsOnlyAfterCommit() {
        var index = new StorageBusSlotIndex<String>();

        index.beginRebuild();
        index.record("iron", 2);
        index.record("iron", 7);
        assertTrue(index.candidates("iron").isEmpty());

        index.commitRebuild();
        var candidates = index.candidates("iron");
        assertEquals(2, candidates.size());
        assertEquals(2, candidates.get(0));
        assertEquals(7, candidates.get(1));
    }

    @Test
    void partialOrAbortedRebuildKeepsPublishedIndex() {
        var index = new StorageBusSlotIndex<String>();
        index.beginRebuild();
        index.record("gold", 4);
        index.commitRebuild();

        index.beginRebuild();
        index.record("iron", 9);
        assertEquals(4, index.candidates("gold").get(0));

        index.abortRebuild();
        assertEquals(4, index.candidates("gold").get(0));
        assertTrue(index.candidates("iron").isEmpty());
    }

    @Test
    void committedRebuildRemovesKeysThatDisappeared() {
        var index = new StorageBusSlotIndex<String>();
        index.beginRebuild();
        index.record("gold", 4);
        index.commitRebuild();

        index.beginRebuild();
        index.record("iron", 1);
        index.commitRebuild();

        assertTrue(index.candidates("gold").isEmpty());
        assertEquals(1, index.candidates("iron").get(0));
    }
}
