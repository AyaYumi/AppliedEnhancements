package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class NetworkItemContextMenuApiTest {
    @Test
    void registrationsUsePriorityThenStableIdOrder() {
        register("z_low", 10);
        register("b_high", 20);
        register("a_high", 20);

        var ids = NetworkItemContextMenuApi.registrations().stream()
                .filter(entry -> entry.id().getNamespace().equals("contextmenuorder"))
                .map(entry -> entry.id().getPath())
                .toList();

        assertEquals(List.of("a_high", "b_high", "z_low"), ids);
    }

    @Test
    void duplicateProviderIdsAreRejected() {
        var id = ResourceLocation.fromNamespaceAndPath(
                "contextmenutest", "duplicate");
        NetworkItemContextMenuApi.register(id, 0, context -> List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkItemContextMenuApi.register(
                        id, 1, context -> List.of()));
    }

    private static void register(String path, int priority) {
        NetworkItemContextMenuApi.register(
                ResourceLocation.fromNamespaceAndPath("contextmenuorder", path),
                priority,
                context -> List.of());
    }
}
