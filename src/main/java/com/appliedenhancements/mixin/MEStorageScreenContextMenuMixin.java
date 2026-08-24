package com.appliedenhancements.mixin;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.common.RepoSlot;
import appeng.menu.me.common.MEStorageMenu;
import com.appliedenhancements.client.menu.ClientNetworkItemContext;
import com.appliedenhancements.client.menu.NetworkItemContextMenu;
import com.appliedenhancements.client.menu.ItemContextMenuKeyMapping;
import com.appliedenhancements.integration.ae2.NetworkItemContextMenuScreenBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;
import appeng.client.gui.widgets.AETextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the item action menu to every AE2 storage-screen subclass. */
@Mixin(value = MEStorageScreen.class, remap = false)
public abstract class MEStorageScreenContextMenuMixin
        implements NetworkItemContextMenuScreenBridge {
    @Shadow
    @Final
    private AETextField searchField;

    @Shadow
    private void setSearchText(String text) {
        throw new AssertionError();
    }

    @Unique
    private final NetworkItemContextMenu appliedenhancements$networkItemMenu =
            new NetworkItemContextMenu();

    @Inject(method = "init", at = @At("HEAD"))
    private void appliedenhancements$closeNetworkItemMenuOnInit(
            CallbackInfo callback) {
        appliedenhancements$networkItemMenu.close();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void appliedenhancements$closeNetworkItemMenuOnRemoved(
            CallbackInfo callback) {
        appliedenhancements$networkItemMenu.close();
    }

    @Override
    public void appliedenhancements$setTerminalSearch(String searchText) {
        String normalized = searchText == null ? "" : searchText;
        searchField.setValue(normalized);
        searchField.setFocused(true);
        setSearchText(normalized);
    }

    @Override
    public boolean appliedenhancements$networkItemMenuMouseClicked(
            double mouseX, double mouseY, int button) {
        if (appliedenhancements$networkItemMenu.isOpen()) {
            return appliedenhancements$networkItemMenu.mouseClicked(
                    mouseX, mouseY, button);
        }
        if (!ItemContextMenuKeyMapping.matchesMouse(button)) {
            return false;
        }

        return appliedenhancements$openNetworkItemMenu(mouseX, mouseY);
    }

    @Override
    public boolean appliedenhancements$openNetworkItemMenu(
            double mouseX, double mouseY) {

        var screen = (MEStorageScreen<?>) (Object) this;
        if (!screen.getMenu().getCarried().isEmpty()) {
            // Preserve AE2's held-container and carried-stack right-click actions.
            return false;
        }
        RepoSlot slot = appliedenhancements$findRepoSlot(screen, mouseX, mouseY);
        if (slot == null || slot.getEntry() == null || slot.getEntry().getWhat() == null) {
            return false;
        }

        var context = new ClientNetworkItemContext(
                (MEStorageMenu) screen.getMenu(),
                slot.getEntry(),
                this::appliedenhancements$setTerminalSearch);
        appliedenhancements$networkItemMenu.open(
                (int) mouseX,
                (int) mouseY,
                screen.width,
                screen.height,
                context);
        return true;
    }

    @Override
    public boolean appliedenhancements$networkItemMenuMouseScrolled(
            double mouseX, double mouseY, double deltaX, double deltaY) {
        return appliedenhancements$networkItemMenu.mouseScrolled(
                mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean appliedenhancements$networkItemMenuKeyPressed(
            int keyCode, int scanCode, int modifiers) {
        return appliedenhancements$networkItemMenu.keyPressed(
                keyCode, scanCode, modifiers);
    }

    @Override
    public boolean appliedenhancements$networkItemMenuCharTyped(
            char codePoint, int modifiers) {
        return appliedenhancements$networkItemMenu.charTyped(
                codePoint, modifiers);
    }

    @Override
    public void appliedenhancements$renderNetworkItemMenu(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        appliedenhancements$networkItemMenu.render(
                graphics, mouseX, mouseY, partialTick);
    }

    @Unique
    private static RepoSlot appliedenhancements$findRepoSlot(
            MEStorageScreen<?> screen,
            double mouseX,
            double mouseY) {
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        for (Slot slot : screen.getMenu().slots) {
            if (slot instanceof RepoSlot repoSlot
                    && mouseX >= left + slot.x
                    && mouseX < left + slot.x + 16
                    && mouseY >= top + slot.y
                    && mouseY < top + slot.y + 16) {
                return repoSlot;
            }
        }
        return null;
    }
}
