package com.appliedenhancements.client.plan;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import com.appliedenhancements.AppliedEnhancements;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = AppliedEnhancements.MODID, value = Dist.CLIENT)
public final class AelisPlanScreenEvents {
    private static final Set<CraftConfirmScreen> ORIGINAL_VIEW =
            Collections.newSetFromMap(new WeakHashMap<>());

    private AelisPlanScreenEvents() {}

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        // Do not replace other addons' subclasses, unfinished plans or non-AELIS results.
        if (event.getScreen().getClass() != CraftConfirmScreen.class) return;
        var original = (CraftConfirmScreen) event.getScreen();
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != original || ORIGINAL_VIEW.contains(original)
                || original.getMenu().getPlan() == null
                || !AelisPlanLayout.fit(original.width, original.height).usable()
                || !(original.getMenu() instanceof AelisCalculationPathMenuBridge bridge)
                || bridge.molecularmanipulator$getCalculationPath() != AelisCalculationPath.AELIS) return;
        original.switchToScreen(new AelisCraftConfirmScreen(original, minecraft.player.getInventory()));
        event.setCanceled(true);
    }

    static void showOriginal(AelisCraftConfirmScreen current, CraftConfirmScreen original) {
        ORIGINAL_VIEW.add(original);
        current.switchToScreen(original);
    }
}
