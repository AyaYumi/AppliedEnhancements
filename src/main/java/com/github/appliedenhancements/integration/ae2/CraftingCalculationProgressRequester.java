package com.github.appliedenhancements.integration.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record CraftingCalculationProgressRequester(
        ICraftingSimulationRequester delegate,
        CraftingCalculationProgressHandle progress)
        implements ICraftingSimulationRequester {
    public CraftingCalculationProgressRequester {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(progress, "progress");
    }

    @Nullable
    @Override
    public IActionSource getActionSource() {
        return delegate.getActionSource();
    }

    @Nullable
    @Override
    public IGridNode getGridNode() {
        return delegate.getGridNode();
    }
}
