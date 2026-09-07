package com.github.appliedenhancements.integration.ae2;

import appeng.api.stacks.AEKey;
import java.util.Map;

/** Internal snapshot of stock hidden by AE2's final-output ignore operation. */
public interface AelisIgnoredSeedInventory {
    Map<AEKey, Long> appliedenhancements$ignoredSeeds();
}
