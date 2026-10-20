package com.appliedenhancements.client.plan;

import appeng.api.stacks.AEKey;
import java.util.List;

/** JEI installs the adapter only while its runtime is available. */
public final class OptionalPlanBookmarks {
    private static Handler handler;

    private OptionalPlanBookmarks() {}

    public static void install(Handler adapter) { handler = adapter; }
    public static void uninstall(Handler adapter) { if (handler == adapter) handler = null; }
    public static boolean available() { return handler != null; }

    public static Result add(List<AEKey> keys) {
        return handler == null ? new Result(0, keys.size()) : handler.add(List.copyOf(keys));
    }

    public record Result(int added, int unsupported) {}

    @FunctionalInterface
    public interface Handler {
        Result add(List<AEKey> keys);
    }
}
