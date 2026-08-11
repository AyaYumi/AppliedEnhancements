package com.github.appliedenhancements.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.inv.CraftingSimulationState;

import java.util.Map;

public interface OmniCraftingTreeProcessBridge {
    IPatternDetails molecularmanipulator$getDetails();

    Map<CraftingTreeNode, Long> molecularmanipulator$getChildNodes();

    boolean molecularmanipulator$hasContainerItems();

    boolean molecularmanipulator$limitsQuantity();

    boolean molecularmanipulator$isPossible();

    void molecularmanipulator$setPossible(boolean possible);

    void molecularmanipulator$request(CraftingSimulationState inventory,
            long patternTimes) throws CraftBranchFailure, InterruptedException;

    boolean molecularmanipulator$hasMultiplePaths();
}
