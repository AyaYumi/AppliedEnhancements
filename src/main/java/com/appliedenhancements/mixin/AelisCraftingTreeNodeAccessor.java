package com.appliedenhancements.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import com.github.appliedenhancements.integration.ae2.AelisCraftingTreeNodeBridge;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.ArrayList;

@Mixin(value = CraftingTreeNode.class, remap = false)
public interface AelisCraftingTreeNodeAccessor extends AelisCraftingTreeNodeBridge {
    @Override
    @Accessor("what")
    AEKey molecularmanipulator$getWhat();

    @Override
    @Accessor("amount")
    long molecularmanipulator$getAmount();

    @Override
    @Accessor("parentInput")
    IPatternDetails.IInput molecularmanipulator$getParentInput();

    @Override
    @Accessor("level")
    Level molecularmanipulator$getLevel();

    @Override
    @Accessor("canEmit")
    boolean molecularmanipulator$canEmit();

    @Override
    @Accessor("nodes")
    ArrayList<CraftingTreeProcess> molecularmanipulator$getProcesses();

    @Override
    @Invoker("buildChildPatterns")
    void molecularmanipulator$buildChildPatterns();

    @Override
    @Invoker("getValidItemTemplates")
    Iterable<InputTemplate> molecularmanipulator$getValidItemTemplates(ICraftingInventory inventory);

    @Override
    @Invoker("request")
    void molecularmanipulator$request(CraftingSimulationState inventory, long requestedAmount,
            KeyCounter containerItems) throws CraftBranchFailure, InterruptedException;
}
