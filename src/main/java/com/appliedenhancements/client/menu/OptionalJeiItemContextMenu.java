package com.appliedenhancements.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * JEI-free dispatch point installed by the optional JEI plugin at runtime.
 * Keeping JEI types out of this class allows the client to start without JEI.
 */
public final class OptionalJeiItemContextMenu {
    private static final Handler NOOP = new Handler() {
    };
    private static volatile Handler handler = NOOP;

    private OptionalJeiItemContextMenu() {
    }

    public static void install(Handler newHandler) {
        handler = newHandler == null ? NOOP : newHandler;
    }

    public static void uninstall(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = NOOP;
        }
    }

    public static Handler handler() {
        return handler;
    }

    public interface Handler {
        default boolean trigger(
                Screen screen, double mouseX, double mouseY) {
            return false;
        }

        default boolean mouseClicked(
                Screen screen, double mouseX, double mouseY, int button) {
            return false;
        }

        default boolean mouseScrolled(
                Screen screen,
                double mouseX,
                double mouseY,
                double deltaX,
                double deltaY) {
            return false;
        }

        default boolean keyPressed(
                Screen screen, int keyCode, int scanCode, int modifiers) {
            return false;
        }

        default boolean charTyped(
                Screen screen, char codePoint, int modifiers) {
            return false;
        }

        default void render(
                Screen screen,
                GuiGraphics graphics,
                int mouseX,
                int mouseY,
                float partialTick) {
        }

        default void close() {
        }

    }
}
