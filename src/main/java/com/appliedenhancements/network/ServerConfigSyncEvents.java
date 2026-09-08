package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import com.appliedenhancements.network.NetworkHandler;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.ApiStatus;

/** Sends the authoritative server settings on login and after live config reloads. */
@ApiStatus.Internal
public final class ServerConfigSyncEvents {
    private ServerConfigSyncEvents() {
    }

    public static void register(IEventBus modEventBus) {
        MinecraftForge.EVENT_BUS.addListener(ServerConfigSyncEvents::onPlayerLoggedIn);
        modEventBus.addListener(ServerConfigSyncEvents::onConfigReloaded);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.sendToPlayer(player, ServerConfigSyncPayload.currentServerValues());
        }
    }

    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (!AppliedEnhancements.MODID.equals(event.getConfig().getModId())) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            var payload = ServerConfigSyncPayload.currentServerValues();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                NetworkHandler.sendToPlayer(player, payload);
            }
        });
    }
}
