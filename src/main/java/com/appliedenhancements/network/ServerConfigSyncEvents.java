package com.appliedenhancements.network;

import com.appliedenhancements.AppliedEnhancements;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.ApiStatus;

/** Sends the authoritative server settings on login and after live config reloads. */
@ApiStatus.Internal
public final class ServerConfigSyncEvents {
    private ServerConfigSyncEvents() {
    }

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(ServerConfigSyncEvents::onPlayerLoggedIn);
        modEventBus.addListener(ServerConfigSyncEvents::onConfigReloaded);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, ServerConfigSyncPayload.currentServerValues());
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
                PacketDistributor.sendToPlayer(player, payload);
            }
        });
    }
}
