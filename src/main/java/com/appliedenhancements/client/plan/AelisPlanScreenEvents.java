package com.appliedenhancements.client.plan;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = AppliedEnhancements.MODID, value = Dist.CLIENT)
public final class AelisPlanScreenEvents {
    private static final Set<CraftConfirmScreen> ORIGINAL_VIEW =
            Collections.newSetFromMap(new WeakHashMap<>());

    private AelisPlanScreenEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOpening(ScreenEvent.Opening event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player != null && shouldReplace(event.getNewScreen(),
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight())) {
            // The menu is already open; replace only its client view before the native screen is initialized.
            event.setNewScreen(new AelisCraftConfirmScreen(
                    (CraftConfirmScreen) event.getNewScreen(), minecraft.player.getInventory()));
        }
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        var minecraft = Minecraft.getInstance();
        var screen = event.getScreen();
        // Also handle enabling the setting while an original confirmation screen is already open.
        if (minecraft.player == null || minecraft.screen != screen
                || !shouldReplace(screen, screen.width, screen.height)) return;
        var original = (CraftConfirmScreen) screen;
        original.switchToScreen(new AelisCraftConfirmScreen(original, minecraft.player.getInventory()));
        event.setCanceled(true);
    }

    private static boolean shouldReplace(Screen screen, int width, int height) {
        // Respect other addons' subclasses and an explicit choice to use the original view.
        if (screen == null || screen.getClass() != CraftConfirmScreen.class
                || !Config.ENABLE_AELIS_PLAN_SCREEN.get()
                || ORIGINAL_VIEW.contains(screen)
                || !AelisPlanLayout.fit(width, height).usable()) return false;
        var menu = ((CraftConfirmScreen) screen).getMenu();
        return menu instanceof AelisCalculationPathMenuBridge bridge
                && (menu.getPlan() == null
                        || bridge.molecularmanipulator$getCalculationPath() == AelisCalculationPath.AELIS);
    }

    static void showOriginal(AelisCraftConfirmScreen current, CraftConfirmScreen original) {
        ORIGINAL_VIEW.add(original);
        current.switchToScreen(original);
    }
}
