package com.appliedenhancements.storage;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import java.util.Set;

public interface InfiniteStorageNetworkAccess {
    Set<AEKey> appliedenhancements$snapshotInfiniteKeys(
            IActionSource source, Iterable<AEKey> candidateKeys);
}
