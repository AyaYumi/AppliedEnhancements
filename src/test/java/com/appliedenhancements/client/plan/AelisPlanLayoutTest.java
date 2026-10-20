package com.appliedenhancements.client.plan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AelisPlanLayoutTest {
    @Test
    void fitsCommonGuiScalesWithoutRowsOverlappingFooter() {
        for (int[] size : new int[][] {{1920, 1080}, {640, 360}, {480, 270}, {427, 240}, {320, 240}}) {
            var layout = AelisPlanLayout.fit(size[0], size[1]);
            assertTrue(layout.usable());
            assertTrue(layout.width() <= size[0] - 12);
            assertTrue(layout.height() <= size[1] - 12);
            assertTrue(layout.rowsBottom() <= layout.footerTop() - 5);
            assertTrue(layout.visibleRows() >= 2);
            assertTrue(layout.tableRight() >= 288);
            assertEquals(layout.width() - 10, layout.tableRight());
        }
    }

    @Test
    void tinyViewportsFallBackToAe2() {
        assertFalse(AelisPlanLayout.fit(300, 200).usable());
    }

    @Test
    void ordinaryColumnsKeepTheirExistingPositions() {
        var layout = AelisPlanLayout.fit(640, 400);
        var columns = layout.columns(false);
        assertEquals(360, layout.width());
        assertEquals(336, columns.craftRight());
        assertEquals(288, columns.storedRight());
        assertEquals(240, columns.demandRight());
        assertEquals(150, columns.nameWidth());
    }

    @Test
    void cyclicColumnFitsEveryUsableWidthWithoutOverlappingAmountsOrNames() {
        for (int width = 312; width <= 640; width++) {
            var layout = AelisPlanLayout.fit(width, 400);
            var columns = layout.columns(true);
            assertTrue(layout.usable());
            assertEquals(64, columns.right() - columns.craftRight());
            assertEquals(48, columns.craftRight() - columns.storedRight());
            assertEquals(48, columns.storedRight() - columns.demandRight());
            assertEquals(6, columns.demandRight() - 44 - (40 + columns.nameWidth()));
            assertTrue(columns.nameWidth() >= 26);
            assertEquals(layout.tableRight() - 14, columns.right());
        }
    }

    @Test
    void footerButtonsKeepSpaceBetweenRowsAndInsideTheBottomBevelAtEveryUsableHeight() {
        for (int height = 232; height <= 1080; height++) {
            var layout = AelisPlanLayout.fit(640, height);
            assertTrue(layout.usable());
            assertTrue(layout.cpuButtonTop() >= layout.footerTop() + 7);
            assertEquals(5, layout.actionButtonTop() - layout.cpuButtonTop() - AelisPlanLayout.FOOTER_BUTTON_HEIGHT);
            int buttonBottom = layout.actionButtonTop() + AelisPlanLayout.FOOTER_BUTTON_HEIGHT;
            // The bevel occupies two pixels. Leave at least four clear pixels before it.
            assertTrue(buttonBottom <= layout.footerBottom() - 2 - 4);
            assertTrue(layout.footerBottom() <= layout.height() - 8);
        }
    }
}
