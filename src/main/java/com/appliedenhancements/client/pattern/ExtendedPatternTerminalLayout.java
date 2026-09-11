package com.appliedenhancements.client.pattern;

import net.minecraft.client.renderer.Rect2i;

/** Slot-row bounds shared by wired and wireless ExtendedAE pattern terminals. */
public final class ExtendedPatternTerminalLayout {
    private ExtendedPatternTerminalLayout() {
    }

    public static Rect2i rows(int guiLeft, int guiTop, int visibleRows) {
        return new Rect2i(guiLeft + 22, guiTop + 51, 9 * 18, visibleRows * 18);
    }
}
