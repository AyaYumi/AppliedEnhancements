package com.appliedenhancements.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import com.appliedenhancements.runtime.SmartDoublingPatternAccess;

/** Detects optional native smart doubling without adding an Applied scaling interface. */
public final class AelisSmartDoublingApi {
    private AelisSmartDoublingApi() {}

    /** True for an existing external batch or a pattern whose native doubling is enabled. */
    public static boolean isExternallyManaged(IPatternDetails pattern) {
        return pattern != null && SmartDoublingPatternAccess.isExternallyManaged(pattern);
    }

    /** True for a provider advertising the optional Useless smart-doubling contract. */
    public static boolean isExternallyManagedProvider(ICraftingProvider provider) {
        return provider != null && SmartDoublingPatternAccess.isExternallyManagedProvider(provider);
    }
}
