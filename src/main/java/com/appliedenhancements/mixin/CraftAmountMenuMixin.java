package com.appliedenhancements.mixin;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftAmountMenu;
import com.appliedenhancements.Config;
import com.appliedenhancements.ae2.ExactCraftingMenuBridge;
import com.appliedenhancements.api.AelisCraftingRequest;
import com.appliedenhancements.network.ExactCraftingAmountPayload;
import com.appliedenhancements.runtime.CraftingSubmissionScope;
import java.math.BigInteger;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extends quantity submission without starting or managing a calculation. */
@Mixin(value = CraftAmountMenu.class, remap = false)
public abstract class CraftAmountMenuMixin implements ExactCraftingMenuBridge {
    @Shadow private AEKey whatToCraft;
    @Unique private BigInteger appliedenhancements$initialExactAmount;

    @Override
    public BigInteger appliedenhancements$getInitialExactAmount() {
        return appliedenhancements$initialExactAmount;
    }

    @Override
    public void appliedenhancements$setInitialExactAmount(BigInteger amount) {
        appliedenhancements$initialExactAmount = amount;
    }

    @Override
    public void appliedenhancements$confirmExact(BigInteger amount, boolean missing, boolean autoStart) {
        var menu = (CraftAmountMenu) (Object) this;
        if (menu.isClientSide() || whatToCraft == null || amount.signum() <= 0
                || !menu.stillValid(menu.getPlayer())) return;
        if (amount.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
            menu.confirm(amount.intValueExact(), missing, autoStart);
            return;
        }
        var mode = Config.MAX_CRAFTING_ORDER_AMOUNT.get();
        if (!mode.isEnabled() || amount.bitLength() > 63 && !mode.supportsBigInteger()) return;

        // The host's int-valued missing calculation cannot subtract a long-sized stock count.
        if (missing && menu.getTarget() instanceof IActionHost host && host.getActionableNode() != null) {
            long stored = host.getActionableNode().getGrid().getStorageService()
                    .getCachedInventory().get(whatToCraft);
            amount = amount.subtract(BigInteger.valueOf(Math.max(0, stored))).max(BigInteger.ZERO);
        }
        var request = new AelisCraftingRequest(whatToCraft, amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        try (var scope = CraftingSubmissionScope.withRequest(request)) {
            menu.confirm(amount.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact(), false, autoStart);
        }
    }

    @Inject(method = "setWhatToCraft", at = @At("RETURN"))
    private void appliedenhancements$restoreSubmittedQuantity(AEKey what, int amount, CallbackInfo callback) {
        var request = CraftingSubmissionScope.request();
        var menu = (CraftAmountMenu) (Object) this;
        if (request != null && request.output().equals(what)
                && menu.getPlayer() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new ExactCraftingAmountPayload(
                    menu.containerId, request.amount().toString(), false, false));
        }
    }
}
