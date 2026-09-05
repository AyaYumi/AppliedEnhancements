package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeProcessBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(value = CraftingTreeProcess.class, remap = false)
public interface AelisCraftingTreeProcessAccessor extends AelisCraftingTreeProcessBridge {
    @Override
    @Accessor("details")
    IPatternDetails molecularmanipulator$getDetails();

    @Override
    @Accessor("nodes")
    Map<CraftingTreeNode, Long> molecularmanipulator$getChildNodes();

    @Override
    @Accessor("containerItems")
    boolean molecularmanipulator$hasContainerItems();

    @Override
    @Accessor("limitQty")
    boolean molecularmanipulator$limitsQuantity();

    @Override
    @Accessor("possible")
    boolean molecularmanipulator$isPossible();

    @Override
    @Accessor("possible")
    void molecularmanipulator$setPossible(boolean possible);

    @Override
    @Invoker("request")
    void molecularmanipulator$request(CraftingSimulationState inventory,
            long patternTimes) throws CraftBranchFailure, InterruptedException;

    @Override
    @Invoker("hasMultiplePaths")
    boolean molecularmanipulator$hasMultiplePaths();
}
