package com.appliedenhancements.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class CraftingPathPresentationTest {
    @Test
    void aelisTitleUsesSakuraPink() {
        var color = CraftingPathPresentation.titleStyle().getColor();

        assertNotNull(color);
        assertEquals(0xE86FA7, color.getValue());
    }
}
