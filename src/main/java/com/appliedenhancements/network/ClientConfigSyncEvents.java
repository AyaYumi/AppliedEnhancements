package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.ApiStatus;

/** Prevents a server snapshot from leaking into a later connection or main-menu state. */
@ApiStatus.Internal
@EventBusSubscriber(modid = AppliedEnhancements.MODID, value = Dist.CLIENT)
public final class ClientConfigSyncEvents {
    private ClientConfigSyncEvents() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        resetConnectionState(event.getPlayer());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetConnectionState(event.getPlayer());
    }

    private static void resetConnectionState(LocalPlayer player) {
        Object playerMenu = player == null ? null : player.containerMenu;
        Object screenMenu = null;
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            screenMenu = containerScreen.getMenu();
        }
        ClientCraftingProgressReset.resetForConnectionChange(playerMenu, screenMenu);
    }
}
