package com.github.appliedenhancements.integration.ae2;

import appeng.me.cluster.implementations.CraftingCPUCluster;

public interface OmniCraftingServiceBridge {
    void molecularmanipulator$registerOmniCpu(CraftingCPUCluster cluster);

    void molecularmanipulator$unregisterOmniCpu(CraftingCPUCluster cluster);
}
