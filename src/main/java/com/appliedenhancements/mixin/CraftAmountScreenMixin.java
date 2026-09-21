package com.appliedenhancements.mixin;

import appeng.client.gui.me.crafting.CraftAmountScreen;
import appeng.client.gui.widgets.NumberEntryWidget;
import com.appliedenhancements.ae2.*;
import com.appliedenhancements.network.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.components.Button;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.*;

@Mixin(value = CraftAmountScreen.class, remap = false)
public abstract class CraftAmountScreenMixin {
    @Shadow @Final private Button next;
    @Shadow @Final private NumberEntryWidget amountToCraft;
    @Shadow private boolean amountInitialized;

    @WrapMethod(method = "updateBeforeRender")
    private void appliedenhancements$updateExact(Operation<Void> original) {
        original.call();
        if (!ServerConfigSyncState.isLongRangeCraftingEnabled()) return;
        var widget = (LongNumberEntryWidgetBridge) amountToCraft;
        widget.appliedenhancements$enableExactInput();
        amountToCraft.setMaxValue(ServerConfigSyncState.getMaxCraftingOrderAmount());
        var menu = ((CraftAmountScreen) (Object) this).getMenu();
        var bridge = (ExactCraftingMenuBridge) menu;
        if (amountInitialized && bridge.appliedenhancements$getInitialExactAmount() != null) {
            widget.appliedenhancements$setExactValue(bridge.appliedenhancements$getInitialExactAmount());
            bridge.appliedenhancements$setInitialExactAmount(null);
        }
        next.active = widget.appliedenhancements$getExactValue().isPresent();
    }

    // Wrap optional mods' legacy long-valued HEAD hooks as well as the original method.
    @WrapMethod(method = "confirm")
    private void appliedenhancements$confirmExact(Operation<Void> original) {
        if (!ServerConfigSyncState.isLongRangeCraftingEnabled()) { original.call(); return; }
        ((LongNumberEntryWidgetBridge) amountToCraft).appliedenhancements$getExactValue().ifPresent(amount ->
            PacketDistributor.sendToServer(new ExactCraftingAmountPayload(
                ((CraftAmountScreen) (Object) this).getMenu().containerId, amount.toString(),
                amountToCraft.startsWithEquals(), CraftAmountScreen.hasShiftDown())));
    }
}
