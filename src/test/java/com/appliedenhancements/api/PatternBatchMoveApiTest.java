package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PatternBatchMoveApiTest {
    @Test
    void acceptsBoundedSourcesAndTargets() {
        var sources = PatternBatchMoveApi.copySources(List.of(
                new PatternSlotRef(1, 0),
                new PatternSlotRef(2, 3)));
        var targets = PatternBatchMoveApi.copyTargets(List.of(10L, 11L));

        assertEquals(2, sources.size());
        assertEquals(List.of(10L, 11L), targets);
    }

    @Test
    void rejectsInvalidSourceAndTargetCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PatternBatchMoveApi.copySources(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> PatternBatchMoveApi.copyTargets(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> PatternBatchMoveApi.copySources(IntStream
                        .range(0, PatternBatchMoveApi.MAX_SOURCES + 1)
                        .mapToObj(index -> new PatternSlotRef(1, index))
                        .toList()));
    }

    @Test
    void resultDistinguishesCommitAndFailure() {
        var success = PatternBatchMoveApi.Result.success(24);
        var failure = PatternBatchMoveApi.Result.failure(
                PatternBatchMoveApi.Failure.NOT_ENOUGH_SPACE);

        assertTrue(success.success());
        assertEquals(24, success.moved());
        assertEquals(PatternBatchMoveApi.Failure.NOT_ENOUGH_SPACE, failure.failure());
    }

    @Test
    void requestRejectsInvalidPreferredSlot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PatternBatchMoveApi.Request(
                        1, List.of(new PatternSlotRef(2, 0)), List.of(3L), -2));
    }
}
