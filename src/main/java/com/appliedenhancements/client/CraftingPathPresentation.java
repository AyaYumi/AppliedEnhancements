package com.appliedenhancements.client;

import net.minecraft.network.chat.Style;

/** Visual identity for the AELIS label shown in crafting titles. */
public final class CraftingPathPresentation {
    public static final int AELIS_TITLE_RGB = 0xE86FA7;

    private CraftingPathPresentation() {
    }

    public static Style titleStyle() {
        return Style.EMPTY.withColor(AELIS_TITLE_RGB);
    }
}
