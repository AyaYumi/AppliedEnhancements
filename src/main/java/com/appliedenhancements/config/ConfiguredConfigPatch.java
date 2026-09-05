package com.appliedenhancements.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.List;

/** Applies Configured's changed values without replacing their parent sections. */
public final class ConfiguredConfigPatch {
    private ConfiguredConfigPatch() {
    }

    public static void apply(CommentedConfig target, UnmodifiableConfig changes) {
        apply(target, changes, new ArrayList<>());
    }

    private static void apply(
            CommentedConfig target, UnmodifiableConfig changes, List<String> path) {
        for (var entry : changes.entrySet()) {
            path.add(entry.getKey());
            if (entry.getValue() instanceof UnmodifiableConfig section) {
                apply(target, section, path);
            } else {
                target.set(path, entry.getValue());
            }
            path.remove(path.size() - 1);
        }
    }
}
