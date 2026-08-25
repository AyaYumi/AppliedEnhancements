package com.appliedenhancements.client.menu;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.appliedenhancements.AppliedEnhancements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Adds clickable JEI searches to item text shared through player chat. */
@EventBusSubscriber(modid = AppliedEnhancements.MODID, value = Dist.CLIENT)
public final class ItemChatLinkClientEvents {
    private ItemChatLinkClientEvents() {
    }

    @SubscribeEvent
    public static void onPlayerChat(ClientChatReceivedEvent.Player event) {
        ItemChatLink.decorate(event.getMessage()).ifPresent(event::setMessage);
    }

    @SubscribeEvent
    public static void registerClientCommand(
            RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal(ItemChatLink.SEARCH_COMMAND)
                .then(argument("id", ResourceLocationArgument.id())
                        .executes(context -> searchInJei(
                                ResourceLocationArgument.getId(
                                        context, "id")))));
    }

    private static int searchInJei(ResourceLocation id) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (!OptionalJeiItemContextMenu.handler().search(id)) {
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "message.appliedenhancements.item_chat_link.jei_unavailable"),
                        true);
            }
            return 0;
        }
        if (player != null) {
            if (player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
            }
            minecraft.setScreen(new InventoryScreen(player));
            player.displayClientMessage(Component.translatable(
                    "message.appliedenhancements.item_chat_link.search_applied",
                    id.toString()), true);
        }
        return 1;
    }
}
