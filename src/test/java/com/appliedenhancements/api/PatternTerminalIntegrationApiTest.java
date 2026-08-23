package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appliedenhancements.api.PatternTerminalIntegrationApi.Family;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PatternTerminalIntegrationApiTest {
    @Test
    void builtInTerminalFamiliesAreRegisteredExactly() {
        assertTrue(PatternTerminalIntegrationApi.supports(
                "appeng.client.gui.me.patternaccess.PatternAccessTermScreen",
                Family.AE2_PATTERN_ACCESS));
        assertTrue(PatternTerminalIntegrationApi.supports(
                "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal",
                Family.EXTENDEDAE_PATTERN_ACCESS));
        assertFalse(PatternTerminalIntegrationApi.supports(
                "example.CustomScreen", Family.AE2_PATTERN_ACCESS));
    }

    @Test
    void thirdPartyScreenCanRegisterWithoutLoadingItsOptionalClass() {
        var id = ResourceLocation.fromNamespaceAndPath(
                "examplemod", "test_pattern_terminal");
        PatternTerminalIntegrationApi.register(
                id, Family.AE2_PATTERN_ACCESS, "examplemod.client.TestPatternScreen");

        assertTrue(PatternTerminalIntegrationApi.supports(
                "examplemod.client.TestPatternScreen", Family.AE2_PATTERN_ACCESS));
    }
}
