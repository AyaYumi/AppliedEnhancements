package com.appliedenhancements.integration.ae2;

import appeng.client.gui.me.patternaccess.PatternContainerRecord;

/** Internal view of AE2 and ExtendedAE pattern-terminal slot rows. */
public interface PatternTerminalSlotsRowBridge {
    PatternContainerRecord appliedenhancements$getContainer();

    int appliedenhancements$getOffset();

    int appliedenhancements$getSlotCount();
}
