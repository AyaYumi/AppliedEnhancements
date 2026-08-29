package com.appliedenhancements.integration.ae2;

import appeng.client.gui.me.patternaccess.PatternSlot;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Supplies source-machine tooltip lines for compact duplicate pattern rows. */
public interface DuplicatePatternSourceScreenBridge {
    List<Component> appliedenhancements$getPatternSourceTooltip(PatternSlot slot);
}
