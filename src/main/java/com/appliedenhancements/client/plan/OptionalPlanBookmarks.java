package com.appliedenhancements.client.plan;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.integration.emi.EmiPlanBookmarks;
import java.util.List;

/** Prefers a ready EMI runtime; JEI independently owns the install/uninstall slot. */
public final class OptionalPlanBookmarks {
    private static Handler handler;

    private OptionalPlanBookmarks() {}

    public static void install(Handler adapter) { handler = adapter; }
    public static void uninstall(Handler adapter) { if (handler == adapter) handler = null; }
    public static boolean available() { return preferredHandler() != null; }

    public static Result add(List<AEKey> keys) {
        Handler selected = preferredHandler();
        return selected == null ? new Result(0, keys.size()) : selected.add(List.copyOf(keys));
    }

    private static Handler preferredHandler() {
        Handler emi = EmiPlanBookmarks.availableHandler();
        return emi != null ? emi : handler;
    }

    public record Result(int added, int unsupported) {}

    @FunctionalInterface
    public interface Handler {
        Result add(List<AEKey> keys);
    }
}
