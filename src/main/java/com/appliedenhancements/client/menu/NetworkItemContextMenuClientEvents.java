package com.appliedenhancements.client.menu;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.integration.ae2.NetworkItemContextMenuScreenBridge;
import com.appliedenhancements.integration.ae2.PatternQuickMoveScreenBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.minecraft.client.Minecraft;

/** Handles screen inputs whose Minecraft implementations are interface defaults. */
@EventBusSubscriber(modid = AppliedEnhancements.MODID, value = Dist.CLIENT)
public final class NetworkItemContextMenuClientEvents {
    private NetworkItemContextMenuClientEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (OptionalJeiItemContextMenu.handler().mouseClicked(
                event.getScreen(),
                event.getMouseX(),
                event.getMouseY(),
                event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (OptionalJeiItemContextMenu.handler().mouseScrolled(
                event.getScreen(),
                event.getMouseX(),
                event.getMouseY(),
                event.getScrollDeltaX(),
                event.getScrollDeltaY())) {
            event.setCanceled(true);
        } else if (event.getScreen() instanceof NetworkItemContextMenuScreenBridge bridge
                && bridge.appliedenhancements$networkItemMenuMouseScrolled(
                        event.getMouseX(),
                        event.getMouseY(),
                        event.getScrollDeltaX(),
                        event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (OptionalJeiItemContextMenu.handler().keyPressed(
                event.getScreen(),
                event.getKeyCode(),
                event.getScanCode(),
                event.getModifiers())) {
            event.setCanceled(true);
        } else if (event.getScreen() instanceof NetworkItemContextMenuScreenBridge bridge
                && bridge.appliedenhancements$networkItemMenuKeyPressed(
                        event.getKeyCode(),
                        event.getScanCode(),
                        event.getModifiers())) {
            event.setCanceled(true);
        } else if (ItemContextMenuKeyMapping.matchesKey(
                event.getKeyCode(), event.getScanCode())) {
            double[] mouse = scaledMousePosition();
            if (OptionalJeiItemContextMenu.handler().trigger(
                    event.getScreen(), mouse[0], mouse[1])) {
                event.setCanceled(true);
            } else if (event.getScreen()
                    instanceof NetworkItemContextMenuScreenBridge bridge
                    && bridge.appliedenhancements$openNetworkItemMenu(
                            mouse[0], mouse[1])) {
                event.setCanceled(true);
            } else if (event.getScreen()
                    instanceof PatternQuickMoveScreenBridge bridge
                    && bridge.appliedenhancements$openQuickMoveContextMenu(
                            mouse[0], mouse[1])) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (OptionalJeiItemContextMenu.handler().charTyped(
                event.getScreen(),
                event.getCodePoint(),
                event.getModifiers())) {
            event.setCanceled(true);
        } else if (event.getScreen() instanceof NetworkItemContextMenuScreenBridge bridge
                && bridge.appliedenhancements$networkItemMenuCharTyped(
                        event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRender(ScreenEvent.Render.Post event) {
        OptionalJeiItemContextMenu.handler().render(
                event.getScreen(),
                event.getGuiGraphics(),
                event.getMouseX(),
                event.getMouseY(),
                event.getPartialTick());
    }

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening event) {
        OptionalJeiItemContextMenu.handler().close();
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        OptionalJeiItemContextMenu.handler().close();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTooltip(RenderTooltipEvent.Pre event) {
        if (ClientContextMenuState.isAnyOpen()) {
            event.setCanceled(true);
        }
    }

    private static double[] scaledMousePosition() {
        var minecraft = Minecraft.getInstance();
        var window = minecraft.getWindow();
        double mouseX = minecraft.mouseHandler.xpos()
                * window.getGuiScaledWidth()
                / Math.max(1, window.getScreenWidth());
        double mouseY = minecraft.mouseHandler.ypos()
                * window.getGuiScaledHeight()
                / Math.max(1, window.getScreenHeight());
        return new double[] { mouseX, mouseY };
    }
}
