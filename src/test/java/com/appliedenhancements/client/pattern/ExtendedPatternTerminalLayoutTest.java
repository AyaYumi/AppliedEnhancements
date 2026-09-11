package com.appliedenhancements.client.pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.renderer.Rect2i;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExtendedPatternTerminalLayoutTest {
    @ParameterizedTest
    @CsvSource({"0, 0, 2", "37, 91, 6", "-5, -9, 12"})
    void everyVisibleSlotCanStartSelection(int guiLeft, int guiTop, int rows) {
        var bounds = ExtendedPatternTerminalLayout.rows(guiLeft, guiTop, rows);
        // ExtendedAE 1.20.1 draws slot (column, row) at (22+18*column, 52+18*row).
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < 9; column++) {
                for (int pixel : new int[] {0, 8, 15}) {
                    assertTrue(start(bounds,
                            guiLeft + 22 + 18 * column + pixel,
                            guiTop + 52 + 18 * row + pixel),
                            "Cannot select row " + row + ", column " + column + ", pixel " + pixel);
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 2", "37, 91, 6", "-5, -9, 12"})
    void headerPlayerInventoryAndOutsideColumnsDoNotStartSelection(int guiLeft, int guiTop, int rows) {
        var bounds = ExtendedPatternTerminalLayout.rows(guiLeft, guiTop, rows);
        assertFalse(start(bounds, guiLeft + 30, guiTop + 40)); // Search/toolbar row.
        assertFalse(start(bounds, guiLeft + 21, guiTop + 60));
        assertFalse(start(bounds, guiLeft + 184, guiTop + 60));
        assertFalse(start(bounds, guiLeft + 30, guiTop + 51 + rows * 18));
    }

    private static boolean start(Rect2i bounds, double mouseX, double mouseY) {
        var controller = new PatternQuickMoveController();
        controller.setEnabled(true);
        return controller.beginSelection(mouseX, mouseY,
                bounds.getX(), bounds.getY(),
                bounds.getX() + bounds.getWidth(), bounds.getY() + bounds.getHeight(), false);
    }
}
