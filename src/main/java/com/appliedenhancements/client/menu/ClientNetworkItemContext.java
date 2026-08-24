package com.appliedenhancements.client.menu;

import appeng.api.stacks.AEKey;
import appeng.helpers.InventoryAction;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.MEStorageMenu;
import com.appliedenhancements.api.NetworkItemContextMenuApi;
import com.appliedenhancements.network.NetworkItemExtractPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.function.Consumer;

/** Built-in client action context for one synchronized ME terminal entry. */
public final class ClientNetworkItemContext
        implements NetworkItemContextMenuApi.Context {
    private final MEStorageMenu menu;
    private final GridInventoryEntry entry;
    private final Consumer<String> terminalSearch;

    public ClientNetworkItemContext(
            MEStorageMenu menu,
            GridInventoryEntry entry,
            Consumer<String> terminalSearch) {
        this.menu = java.util.Objects.requireNonNull(menu, "menu");
        this.entry = java.util.Objects.requireNonNull(entry, "entry");
        this.terminalSearch = java.util.Objects.requireNonNull(
                terminalSearch, "terminalSearch");
        java.util.Objects.requireNonNull(entry.getWhat(), "entry key");
    }

    @Override
    public AEKey key() {
        return entry.getWhat();
    }

    @Override
    public long storedAmount() {
        return entry.getStoredAmount();
    }

    @Override
    public long requestableAmount() {
        return entry.getRequestableAmount();
    }

    @Override
    public boolean craftable() {
        return entry.isCraftable();
    }

    @Override
    public void extractOne() {
        menu.handleInteraction(entry.getSerial(), InventoryAction.PICKUP_SINGLE);
    }

    @Override
    public void extractStack() {
        menu.handleInteraction(entry.getSerial(), InventoryAction.SHIFT_CLICK);
    }

    @Override
    public void extractAmount(long amount) {
        if (amount <= 0) {
            return;
        }
        PacketDistributor.sendToServer(new NetworkItemExtractPayload(
                menu.containerId, entry.getSerial(), amount));
    }

    @Override
    public void requestCraft() {
        if (entry.isCraftable()) {
            menu.handleInteraction(entry.getSerial(), InventoryAction.AUTO_CRAFT);
        }
    }

    @Override
    public void copyName() {
        copyToClipboard(
                key().getDisplayName().getString(),
                "message.appliedenhancements.jei_item_menu.copied_name");
    }

    @Override
    public void copyId() {
        String id = key().getId().toString();
        copyToClipboard(
                id, "message.appliedenhancements.network_item_menu.copied_id");
    }

    @Override
    public void searchSameMod() {
        String modId = key().getModId();
        if (modId != null && !modId.isBlank()) {
            terminalSearch.accept("@" + modId);
        }
    }

    @Override
    public void shareToChat() {
        ClientItemMenuActions.openShareDraft(
                key().getDisplayName(), key().getId().toString());
    }

    private static void copyToClipboard(String value, String messageKey) {
        var minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(value);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    messageKey, value), true);
        }
    }
}
