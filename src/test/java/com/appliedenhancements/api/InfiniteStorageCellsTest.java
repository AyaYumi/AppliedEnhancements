package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InfiniteStorageCellsTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() throws Exception {
        // Plain JUnit does not run Forge's event transformer, which normally
        // adds the default event constructor needed by NetworkHooks bootstrap.
        var listenerLookup = net.minecraftforge.eventbus.api.EventListenerHelper.class
                .getDeclaredMethod("getListenerListInternal", Class.class, boolean.class);
        listenerLookup.setAccessible(true);
        listenerLookup.invoke(null, net.minecraftforge.network.NetworkEvent.class, true);
        for (var nested : net.minecraftforge.network.NetworkEvent.class.getDeclaredClasses()) {
            if (net.minecraftforge.eventbus.api.Event.class.isAssignableFrom(nested)) {
                initializeEventListenerList(listenerLookup, nested);
            }
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static void initializeEventListenerList(java.lang.reflect.Method lookup, Class<?> event) throws Exception {
        var parent = event.getSuperclass();
        if (parent != net.minecraftforge.eventbus.api.Event.class) initializeEventListenerList(lookup, parent);
        lookup.invoke(null, event, true);
    }
    @Test
    void exposesStableItemTagIdentifier() {
        assertEquals(
                "appliedenhancements:infinite_storage_cells",
                InfiniteStorageCells.ITEM_TAG.location().toString());
    }
}
