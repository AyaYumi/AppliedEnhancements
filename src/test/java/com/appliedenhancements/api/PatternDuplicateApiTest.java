package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatternDuplicateApiTest {
    @Test
    void marksEverySlotSharingTheSameOutputKey() {
        var outputs = new LinkedHashMap<PatternSlotRef, String>();
        outputs.put(new PatternSlotRef(10, 0), "minecraft:iron_ingot");
        outputs.put(new PatternSlotRef(10, 5), "minecraft:gold_ingot");
        outputs.put(new PatternSlotRef(20, 3), "minecraft:iron_ingot");

        assertEquals(
                Set.of(new PatternSlotRef(10, 0), new PatternSlotRef(20, 3)),
                PatternDuplicateApi.findDuplicateSlots(outputs));
    }

    @Test
    void uniqueOutputsRemainHidden() {
        var outputs = new LinkedHashMap<PatternSlotRef, String>();
        outputs.put(new PatternSlotRef(1, 0), "minecraft:iron_ingot");
        outputs.put(new PatternSlotRef(1, 1), "minecraft:gold_ingot");

        assertEquals(Set.of(), PatternDuplicateApi.findDuplicateSlots(outputs));
    }

    @Test
    void rejectsNegativeSlotIndexes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PatternSlotRef(1, -1));
    }
}
