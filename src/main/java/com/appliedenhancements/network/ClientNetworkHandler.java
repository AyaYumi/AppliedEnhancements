package com.appliedenhancements.network;

import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

final class ClientNetworkHandler {
    private ClientNetworkHandler() {}
    static <T> void accept(T payload, BiConsumer<T, Player> handler) {
        var player = Minecraft.getInstance().player;
        if (player != null) handler.accept(payload, player);
    }
}
