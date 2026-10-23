package com.appliedenhancements.client.plan;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.appliedenhancements.mixin.AEBaseScreenAccessor;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AppliedEnhancements.MODID, value = Dist.CLIENT)
public final class AelisPlanScreenEvents {
    private static final Set<CraftConfirmScreen> ORIGINAL_VIEW =
            Collections.newSetFromMap(new WeakHashMap<>());

    private AelisPlanScreenEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOpening(ScreenEvent.Opening event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player != null && shouldReplace(event.getNewScreen(),
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight())) {
            event.setNewScreen(new AelisCraftConfirmScreen(
                    (CraftConfirmScreen) event.getNewScreen(), minecraft.player.getInventory()));
        }
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        var minecraft = Minecraft.getInstance();
        var screen = event.getScreen();
        if (minecraft.player == null || minecraft.screen != screen
                || !shouldReplace(screen, screen.width, screen.height)) return;
        var original = (CraftConfirmScreen) screen;
        ((AEBaseScreenAccessor) original).appliedenhancements$switchToScreen(
                new AelisCraftConfirmScreen(original, minecraft.player.getInventory()));
        event.setCanceled(true);
    }

    private static boolean shouldReplace(Screen screen, int width, int height) {
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
        ((AEBaseScreenAccessor) (Object) current).appliedenhancements$switchToScreen(original);
    }
}
