package com.appliedenhancements.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import org.junit.jupiter.api.Test;

class CraftingPathPresentationTest {
    @Test
    void aelisTitleUsesSakuraPink() {
        var color = CraftingPathPresentation.titleStyle(
                AelisCalculationPath.AELIS).getColor();

        assertNotNull(color);
        assertEquals(0xE86FA7, color.getValue());
    }
}
