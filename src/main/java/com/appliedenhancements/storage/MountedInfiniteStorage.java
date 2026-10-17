package com.appliedenhancements.storage;

import appeng.api.storage.MEStorage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/** Per-network cache, created lazily by the transformed storage implementation. */
@ApiStatus.Internal
public final class MountedInfiniteStorage {
    private final InfiniteStorageCellRegistry.MountedClassifier classifier =
            new InfiniteStorageCellRegistry.MountedClassifier();
    private final Set<MEStorage> infiniteStorages = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    public boolean isInfinite(MEStorage storage) {
        return classifier.isInfinite(storage);
    }

    public boolean refresh(MEStorage storage) {
        boolean infinite = classifier.isInfinite(storage);
        if (infinite) infiniteStorages.add(storage);
        else infiniteStorages.remove(storage);
        return infinite;
    }

    public void forget(MEStorage storage) {
        infiniteStorages.remove(storage);
        classifier.forget(storage);
    }

    public List<MEStorage> snapshot() {
        synchronized (infiniteStorages) {
            return new ArrayList<>(infiniteStorages);
        }
    }
}
