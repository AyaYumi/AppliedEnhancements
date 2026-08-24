package com.appliedenhancements.client.menu;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Tracks open client context menus so underlying tooltips can be suppressed. */
public final class ClientContextMenuState {
    private static final Set<Object> OPEN_MENUS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private ClientContextMenuState() {
    }

    public static synchronized void setOpen(Object menu, boolean open) {
        java.util.Objects.requireNonNull(menu, "menu");
        if (open) {
            OPEN_MENUS.add(menu);
        } else {
            OPEN_MENUS.remove(menu);
        }
    }

    public static synchronized boolean isAnyOpen() {
        return !OPEN_MENUS.isEmpty();
    }
}
