package com.appliedenhancements.client;

import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;

/** Visual identity for planner-path labels shown in crafting titles. */
public final class CraftingPathPresentation {
    public static final int AELIS_TITLE_RGB = 0xE86FA7;

    private CraftingPathPresentation() {
    }

    public static Style titleStyle(AelisCalculationPath path) {
        Objects.requireNonNull(path, "path");
        return switch (path) {
            case AELIS -> Style.EMPTY.withColor(AELIS_TITLE_RGB);
            case AE2_FALLBACK -> Style.EMPTY.withColor(ChatFormatting.GOLD);
            case EXTERNAL -> Style.EMPTY.withColor(ChatFormatting.AQUA);
            case AE2_NATIVE -> Style.EMPTY.withColor(ChatFormatting.GRAY);
        };
    }
}
