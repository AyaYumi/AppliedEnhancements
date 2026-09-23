package com.appliedenhancements.mixin;

import java.util.Objects;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ISubMenuHost;
import appeng.menu.MenuOpener;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.slot.AppEngSlot;
import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.Config;
import com.appliedenhancements.ae2.LongCraftingAmountMenuBridge;
import com.appliedenhancements.ae2.LongCraftingConfirmMenuBridge;
import com.appliedenhancements.runtime.DataEnergisticsMenuCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Implements long-range crafting support for AE2's CraftAmountMenu.
 * Allows crafting orders that exceed Integer.MAX_VALUE.
 */
@Mixin(value = CraftAmountMenu.class, remap = false)
public abstract class CraftAmountMenuMixin implements LongCraftingAmountMenuBridge, com.appliedenhancements.ae2.ExactCraftingMenuBridge {
    @Unique private java.math.BigInteger appliedenhancements$initialExactAmount;
    @Override public java.math.BigInteger appliedenhancements$getInitialExactAmount() { return appliedenhancements$initialExactAmount; }
    @Override public void appliedenhancements$setInitialExactAmount(java.math.BigInteger amount) { appliedenhancements$initialExactAmount = amount; }

    @Override public void appliedenhancements$confirmExact(java.math.BigInteger amount, boolean missing, boolean autoStart) {
        var menu = (CraftAmountMenu) (Object) this;
        if (menu.isClientSide() || whatToCraft == null || amount.signum() <= 0) return;
        if (amount.bitLength() <= 63) { appliedenhancements$confirmLong(amount.longValueExact(), missing, autoStart); return; }
        if (!Config.ENABLE_LONG_RANGE_CRAFTING.get() || !Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()
                || Config.MAX_CRAFTING_ORDER_AMOUNT.get() != Long.MAX_VALUE) return;
        new com.appliedenhancements.api.AelisExactRequest(amount);
        if (!(menu.getPlayer() instanceof ServerPlayer player) || menu.getLocator() == null
                || !(menu.getTarget() instanceof IActionHost target) || target.getActionableNode() == null) return;
        if (missing) amount = amount.subtract(java.math.BigInteger.valueOf(Math.max(0,
                target.getActionableNode().getGrid().getStorageService().getCachedInventory().get(whatToCraft))));
        if (amount.signum() <= 0) { host.returnToMainMenu(player, menu); return; }
        MenuOpener.open(CraftConfirmMenu.TYPE, player, menu.getLocator());
        if (player.containerMenu instanceof CraftConfirmMenu confirm) {
            try {
                confirm.setAutoStart(autoStart);
                if (((com.appliedenhancements.ae2.ExactCraftingMenuBridge) confirm).appliedenhancements$planExact(whatToCraft, amount)) {
                    confirm.broadcastChanges(); return;
                }
            } catch (RuntimeException failure) {
                AppliedEnhancements.LOGGER.error("Exact crafting request failed", failure);
            }
            appliedenhancements$closePlanScreen(player, confirm);
            player.sendSystemMessage(Component.translatable("message.appliedenhancements.crafting_plan_stalled"));
        }
    }
    @Shadow
    private AEKey whatToCraft;

    @Shadow
    @Final
    private ISubMenuHost host;

    @Shadow
    @Final
    private AppEngSlot craftingItem;

    @Override
    public void appliedenhancements$setWhatToCraftLong(AEKey whatToCraft, long initialAmount) {
        this.whatToCraft = Objects.requireNonNull(whatToCraft, "whatToCraft");
        this.craftingItem.set(GenericStack.wrapInItemStack(whatToCraft, initialAmount));
    }

    @Override
    public void appliedenhancements$confirmLong(long amount, boolean craftMissingAmount, boolean autoStart) {
        var menu = (CraftAmountMenu) (Object) this;
        if (menu.isClientSide() || this.whatToCraft == null || amount <= 0) {
            return;
        }

        if (!Config.ENABLE_LONG_RANGE_CRAFTING.get()) {
            if (amount <= Integer.MAX_VALUE) {
                menu.confirm((int) amount, craftMissingAmount, autoStart);
            } else {
                menu.getPlayer().sendSystemMessage(Component.translatable(
                        "message.appliedenhancements.long_range_disabled"));
            }
            return;
        }

        // Data Energistics owns the BigInteger-capable Trinity planning and CPU
        // context. Use its public menu state when present, while retaining the
        // standalone Applied Enhancements path when the optional mod is absent.
        if (DataEnergisticsMenuCompat.confirmLongIfAvailable(
                menu, amount, craftMissingAmount, autoStart)) {
            return;
        }

        // Calculate missing amount if requested
        if (craftMissingAmount && menu.getTarget() instanceof IActionHost actionHost) {
            var node = actionHost.getActionableNode();
            if (node != null) {
                long existingAmount = node.getGrid().getStorageService()
                        .getCachedInventory()
                        .get(this.whatToCraft);
                amount = existingAmount >= amount ? 0 : amount - existingAmount;
            }
        }

        // Check maximum crafting amount
        long maximumAmount = Config.MAX_CRAFTING_ORDER_AMOUNT.get();
        if (amount > maximumAmount) {
            menu.getPlayer().sendSystemMessage(Component.translatable(
                    "message.appliedenhancements.crafting_amount_too_large",
                    maximumAmount));
            return;
        }

        if (!(menu.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        var locator = menu.getLocator();
        if (amount <= 0) {
            this.host.returnToMainMenu(player, menu);
            return;
        }
        if (locator == null) {
            return;
        }

        // AE2 keeps a confirmation menu that never received a planning job on
        // screen forever, so refuse to open one for a target that cannot plan.
        if (!(menu.getTarget() instanceof IActionHost targetHost)
                || targetHost.getActionableNode() == null) {
            AppliedEnhancements.LOGGER.warn(
                    "Long-range crafting target {} has no actionable grid node (what={}, amount={})",
                    menu.getTarget(), this.whatToCraft, amount);
            player.sendSystemMessage(Component.translatable(
                    "message.appliedenhancements.crafting_target_unavailable"));
            return;
        }

        AEKey what = this.whatToCraft;
        try {
            MenuOpener.open(CraftConfirmMenu.TYPE, player, locator);
            if (player.containerMenu instanceof CraftConfirmMenu confirmMenu
                    && confirmMenu instanceof LongCraftingConfirmMenuBridge bridge) {
                confirmMenu.setAutoStart(autoStart);
                if (bridge.appliedenhancements$planLong(
                        what, amount, CalculationStrategy.REPORT_MISSING_ITEMS)) {
                    confirmMenu.broadcastChanges();
                    return;
                }
            }
        } catch (Throwable failure) {
            // Other mods may break the long-range path from inside the
            // confirmation menu (for example by dereferencing the argument of
            // setPlan(null)). The exception would otherwise vanish in the packet
            // handler and leave the player on an endless "calculating" screen, so
            // fall through to AE2's own planner.
            AppliedEnhancements.LOGGER.error(
                    "Long-range crafting plan failed for {} x {}; falling back to AE2's native planner",
                    what, amount, failure);
        }

        // AE2's native planner accepts at most Integer.MAX_VALUE, so it can only
        // stand in for amounts within that range.
        if (amount <= Integer.MAX_VALUE
                && player.containerMenu instanceof CraftConfirmMenu confirmMenu) {
            confirmMenu.setAutoStart(autoStart);
            if (confirmMenu.planJob(
                    what,
                    (int) amount,
                    CalculationStrategy.REPORT_MISSING_ITEMS)) {
                confirmMenu.broadcastChanges();
                return;
            }
        }
        if (player.containerMenu instanceof CraftConfirmMenu confirmMenu) {
            appliedenhancements$closePlanScreen(player, confirmMenu);
        }
        player.sendSystemMessage(Component.translatable(
                "message.appliedenhancements.crafting_plan_stalled"));
    }

    /** Closes a plan screen that will never receive a planning job. */
    @Unique
    private static void appliedenhancements$closePlanScreen(
            ServerPlayer player, CraftConfirmMenu confirmMenu) {
        confirmMenu.setValidMenu(false);
        confirmMenu.goBack();
        if (player.containerMenu == confirmMenu) {
            player.closeContainer();
        }
    }
}
