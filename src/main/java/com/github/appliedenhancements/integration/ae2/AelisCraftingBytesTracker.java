package com.github.appliedenhancements.integration.ae2;

import com.appliedenhancements.runtime.CraftingByteEstimate;

public interface AelisCraftingBytesTracker {
    CraftingByteEstimate appliedenhancements$getExactByteEstimate();

    void appliedenhancements$addExactBytes(CraftingByteEstimate bytes);
}
