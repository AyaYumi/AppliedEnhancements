package com.appliedenhancements.integration.jei;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.client.menu.NetworkItemContextMenu;
import com.appliedenhancements.client.menu.NetworkItemContextMenu.ActionEntry;
import com.appliedenhancements.client.menu.OptionalJeiItemContextMenu;
import com.appliedenhancements.client.menu.ItemContextMenuKeyMapping;
import com.appliedenhancements.integration.ae2.NetworkItemContextMenuScreenBridge;
import appeng.api.stacks.AEItemKey;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.helpers.InventoryAction;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.MEStorageMenu;
import java.util.ArrayList;
import java.util.Optional;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.gui.util.CommandUtil;
import mezz.jei.gui.util.GiveAmount;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** JEI ingredient-list adapter for the shared item context menu. */
final class JeiItemContextMenuHandler
        implements OptionalJeiItemContextMenu.Handler {
    private final IJeiRuntime runtime;
    private final NetworkItemContextMenu menu = new NetworkItemContextMenu();

    JeiItemContextMenuHandler(IJeiRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public boolean mouseClicked(
            Screen screen, double mouseX, double mouseY, int button) {
        if (menu.isOpen()) {
            return menu.mouseClicked(mouseX, mouseY, button);
        }
        if (!ItemContextMenuKeyMapping.matchesMouse(button)) {
            return false;
        }
        return trigger(screen, mouseX, mouseY);
    }

    @Override
    public boolean trigger(
            Screen screen, double mouseX, double mouseY) {
        var hovered = runtime.getIngredientListOverlay().isListDisplayed()
                ? runtime.getIngredientListOverlay().getIngredientUnderMouse()
                : java.util.Optional.<ITypedIngredient<?>>empty();
        if (hovered.isEmpty()) {
            hovered = runtime.getBookmarkOverlay().getIngredientUnderMouse();
        }
        if (hovered.isEmpty()) {
            return false;
        }
        try {
            openCaptured(screen, mouseX, mouseY, hovered.orElseThrow());
        } catch (RuntimeException failure) {
            AppliedEnhancements.LOGGER.warn(
                    "Failed to build JEI ingredient context menu", failure);
            menu.close();
        }
        return menu.isOpen();
    }

    @Override
    public boolean mouseScrolled(
            Screen screen,
            double mouseX,
            double mouseY,
            double deltaX,
            double deltaY) {
        return menu.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(
            Screen screen, int keyCode, int scanCode, int modifiers) {
        return menu.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(
            Screen screen, char codePoint, int modifiers) {
        return menu.charTyped(codePoint, modifiers);
    }

    @Override
    public void render(
            Screen screen,
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        menu.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void close() {
        menu.close();
    }

    private <T> void openCaptured(
            Screen screen,
            double mouseX,
            double mouseY,
            ITypedIngredient<T> typed) {
        IIngredientHelper<T> helper = runtime.getIngredientManager()
                .getIngredientHelper(typed.getType());
        T ingredient = typed.getIngredient();
        String name = helper.getDisplayName(ingredient);
        String id = helper.getResourceLocation(ingredient).toString();
        ItemStack cheatStack = helper.getCheatItemStack(ingredient);
        ItemStack itemStack = typed.getItemStack().orElse(ItemStack.EMPTY);
        MEStorageMenu meMenu = screen instanceof MEStorageScreen<?> storageScreen
                ? storageScreen.getMenu()
                : null;
        NetworkItemContextMenuScreenBridge terminalBridge =
                screen instanceof NetworkItemContextMenuScreenBridge bridge
                        ? bridge
                        : null;
        GridInventoryEntry networkEntry = meMenu == null || itemStack.isEmpty()
                ? null
                : findNetworkEntry(meMenu, itemStack).orElse(null);

        var entries = new ArrayList<ActionEntry>();
        entries.add(new ActionEntry(Component.translatable(
                        "gui.appliedenhancements.jei_item_menu.show_recipes"),
                () -> runtime.getRecipesGui().show(
                        runtime.getJeiHelpers().getFocusFactory().createFocus(
                                RecipeIngredientRole.OUTPUT, typed))));
        entries.add(new ActionEntry(Component.translatable(
                        "gui.appliedenhancements.jei_item_menu.show_uses"),
                () -> runtime.getRecipesGui().show(
                        runtime.getJeiHelpers().getFocusFactory().createFocus(
                                RecipeIngredientRole.INPUT, typed))));

        if (networkEntry != null && networkEntry.getStoredAmount() > 0) {
            entries.add(new ActionEntry(Component.translatable(
                            "gui.appliedenhancements.jei_item_menu.extract_one_from_me"),
                    () -> meMenu.handleInteraction(
                            networkEntry.getSerial(), InventoryAction.PICKUP_SINGLE)));
            entries.add(new ActionEntry(Component.translatable(
                            "gui.appliedenhancements.jei_item_menu.extract_stack_from_me"),
                    () -> meMenu.handleInteraction(
                            networkEntry.getSerial(), InventoryAction.SHIFT_CLICK)));
        }
        if (networkEntry != null && networkEntry.isCraftable()) {
            entries.add(new ActionEntry(Component.translatable(
                            "gui.appliedenhancements.jei_item_menu.craft_in_me"),
                    () -> meMenu.handleInteraction(
                            networkEntry.getSerial(), InventoryAction.AUTO_CRAFT)));
        }
        if (terminalBridge != null) {
            entries.add(new ActionEntry(Component.translatable(
                            "gui.appliedenhancements.jei_item_menu.search_in_me"),
                    () -> terminalBridge.appliedenhancements$setTerminalSearch(name)));
        }
        entries.add(new ActionEntry(Component.translatable(
                        "gui.appliedenhancements.item_menu.search_same_mod"),
                () -> runtime.getIngredientFilter().setFilterText(
                        "@" + helper.getResourceLocation(ingredient).getNamespace())));
        entries.add(new ActionEntry(Component.translatable(
                        "gui.appliedenhancements.jei_item_menu.copy_name"),
                () -> copyToClipboard(
                        name,
                        "message.appliedenhancements.jei_item_menu.copied_name")));
        entries.add(new ActionEntry(Component.translatable(
                        "gui.appliedenhancements.network_item_menu.copy_id"),
                () -> copyToClipboard(
                        id,
                        "message.appliedenhancements.network_item_menu.copied_id")));
        if (Internal.getClientToggleState().isCheatItemsEnabled()
                && !cheatStack.isEmpty()) {
            entries.add(new ActionEntry(Component.translatable(
                            "gui.appliedenhancements.jei_item_menu.give_one"),
                    () -> give(cheatStack, GiveAmount.ONE)));
            entries.add(new ActionEntry(Component.translatable(
                            "gui.appliedenhancements.jei_item_menu.give_stack"),
                    () -> give(cheatStack, GiveAmount.MAX)));
        }

        menu.openActions(
                (int) mouseX,
                (int) mouseY,
                screen.width,
                screen.height,
                entries);
    }

    private static void give(ItemStack stack, GiveAmount amount) {
        var command = new CommandUtil(
                Internal.getJeiClientConfigs().getClientConfig(),
                Internal.getServerConnection());
        command.giveStack(stack, amount);
    }

    private static Optional<GridInventoryEntry> findNetworkEntry(
            MEStorageMenu menu, ItemStack stack) {
        AEItemKey key = AEItemKey.of(stack);
        if (key == null || menu.getClientRepo() == null) {
            return Optional.empty();
        }
        return menu.getClientRepo().getAllEntries().stream()
                .filter(entry -> key.equals(entry.getWhat()))
                .findFirst();
    }

    private static void copyToClipboard(String value, String messageKey) {
        var minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(value);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable(messageKey, value), true);
        }
    }
}
