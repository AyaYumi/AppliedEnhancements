package com.appliedenhancements.mixin;

import appeng.client.gui.me.crafting.CraftAmountScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.menu.me.crafting.CraftAmountMenu;
import com.appliedenhancements.ae2.LongNumberEntryWidgetBridge;
import com.appliedenhancements.network.ServerConfigSyncState;
import com.appliedenhancements.network.LongCraftingRequestPayload;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side mixin for CraftAmountScreen to support long value input.
 * Allows players to enter crafting amounts exceeding Integer.MAX_VALUE.
 */
@Mixin(value = CraftAmountScreen.class, remap = false)
public abstract class CraftAmountScreenMixin {
    @Shadow
    @Final
    private Button next;

    @Shadow
    @Final
    private NumberEntryWidget amountToCraft;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void appliedenhancements$enableLongAmounts(CraftAmountMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style, CallbackInfo callback) {
        if (!ServerConfigSyncState.isLongRangeCraftingEnabled()) {
            return;
        }
        // Allow long values in the number entry widget
        this.amountToCraft.setMaxValue(ServerConfigSyncState.getMaxCraftingOrderAmount());
        ((LongNumberEntryWidgetBridge) this.amountToCraft)
                .appliedenhancements$setInputMaxLength(20);
    }

    @Inject(method = "updateBeforeRender", at = @At("RETURN"))
    private void appliedenhancements$validateLongAmount(CallbackInfo callback) {
        if (!ServerConfigSyncState.isLongRangeCraftingEnabled()) {
            this.amountToCraft.setMaxValue(Integer.MAX_VALUE);
            return;
        }
        long maximumAmount = ServerConfigSyncState.getMaxCraftingOrderAmount();
        this.amountToCraft.setMaxValue(maximumAmount);
        ((LongNumberEntryWidgetBridge) this.amountToCraft)
                .appliedenhancements$setInputMaxLength(20);
        // Enable next button only if amount is positive
        long amount = ((LongNumberEntryWidgetBridge) this.amountToCraft)
                .appliedenhancements$getExactLongValue()
                .orElse(0);
        this.next.active = amount > 0 && amount <= maximumAmount;
    }

    @Inject(method = "confirm", at = @At("HEAD"), cancellable = true)
    private void appliedenhancements$confirmLongAmount(CallbackInfo callback) {
        if (!ServerConfigSyncState.isLongRangeCraftingEnabled()) {
            return;
        }
        long amount = ((LongNumberEntryWidgetBridge) this.amountToCraft)
                .appliedenhancements$getExactLongValue()
                .orElse(0);
        if (amount > 0) {
            // Send long crafting request to server
            PacketDistributor.sendToServer(new LongCraftingRequestPayload(
                    amount,
                    this.amountToCraft.startsWithEquals(),
                    CraftAmountScreen.hasShiftDown()));
        }
        callback.cancel();
    }
}
