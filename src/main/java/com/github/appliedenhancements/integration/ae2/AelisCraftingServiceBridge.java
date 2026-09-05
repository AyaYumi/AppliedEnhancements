package com.github.appliedenhancements.integration.ae2;

import appeng.me.cluster.implementations.CraftingCPUCluster;

public interface AelisCraftingServiceBridge {
    void molecularmanipulator$registerAelisCpu(CraftingCPUCluster cluster);

    void molecularmanipulator$unregisterAelisCpu(CraftingCPUCluster cluster);
}
