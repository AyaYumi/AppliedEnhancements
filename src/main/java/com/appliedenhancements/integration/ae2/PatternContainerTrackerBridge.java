package com.appliedenhancements.integration.ae2;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternContainer;

/** Internal view of AE2's server-side pattern-container tracker. */
public interface PatternContainerTrackerBridge {
    PatternContainer appliedenhancements$getContainer();

    InternalInventory appliedenhancements$getServerInventory();

    PatternContainerGroup appliedenhancements$getGroup();
}
