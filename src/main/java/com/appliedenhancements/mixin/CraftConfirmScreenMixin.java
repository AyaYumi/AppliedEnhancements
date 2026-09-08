package com.appliedenhancements.mixin;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import com.appliedenhancements.client.CraftingPathPresentation;
import com.appliedenhancements.client.CraftingStorageFormatter;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressMenuBridge;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressPhase;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressSnapshot;
import com.appliedenhancements.network.ServerConfigSyncState;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathMenuBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftConfirmScreen.class, remap = false, priority = 1100)
public abstract class CraftConfirmScreenMixin
        extends appeng.client.gui.AEBaseScreen<appeng.menu.me.crafting.CraftConfirmMenu> {
    protected CraftConfirmScreenMixin(appeng.menu.me.crafting.CraftConfirmMenu menu,
            net.minecraft.world.entity.player.Inventory inventory, Component title,
            appeng.client.gui.style.ScreenStyle style) {
        super(menu, inventory, title, style);
    }

    // ExtendedAE Plus rewrites the title at RETURN. Preserve our engine label
    // after that formatting pass, while leaving other external planners alone.
    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void appliedenhancements$keepAelisResultTitle(CallbackInfo callback) {
        var menu = ((CraftConfirmScreen) (Object) this).getMenu();
        if (menu.getPlan() != null && menu instanceof AelisCalculationPathMenuBridge bridge) {
            var path = bridge.molecularmanipulator$getCalculationPath();
            if (path == AelisCalculationPath.AELIS || path == AelisCalculationPath.AE2_FALLBACK) {
                setTextContent("dialog_title", appliedenhancements$appendCalculationPath(Component.empty()));
            }
        }
    }

    @ModifyArg(method = "updateBeforeRender", at = @At(value = "INVOKE",
            target = "Lappeng/client/gui/me/crafting/CraftConfirmScreen;setTextContent(Ljava/lang/String;Lnet/minecraft/network/chat/Component;)V",
            ordinal = 0), index = 1)
    private Component appliedenhancements$appendCalculationPath(Component title) {
        var screen = (CraftConfirmScreen) (Object) this;
        var plan = screen.getMenu().getPlan();
        if (ServerConfigSyncState.isProgressDisplayEnabled()
                && plan == null
                && screen.getMenu() instanceof CraftingCalculationProgressMenuBridge progressBridge) {
            var progress = progressBridge.molecularmanipulator$getCalculationProgress();
            if (progress.phase() != CraftingCalculationProgressPhase.IDLE) {
                return appliedenhancements$progressTitle(progress);
            }
        }
        if (plan == null
                || !(screen.getMenu() instanceof AelisCalculationPathMenuBridge bridge)) {
            return title;
        }

        var path = bridge.molecularmanipulator$getCalculationPath();
        String translationKey = switch (path) {
            case AELIS -> "gui.appliedenhancements.calculation_result.path.aelis";
            case AE2_FALLBACK ->
                    "gui.appliedenhancements.calculation_result.path.ae2_fallback";
            case EXTERNAL -> "gui.appliedenhancements.calculation_result.path.external";
            case AE2_NATIVE -> "gui.appliedenhancements.calculation_result.path.ae2_native";
        };

        Component pathLabel = Component.translatable(translationKey)
                .withStyle(CraftingPathPresentation.titleStyle(path));
        if (ServerConfigSyncState.isProgressDisplayEnabled()
                && screen.getMenu() instanceof CraftingCalculationProgressMenuBridge progressBridge) {
            var progress = progressBridge.molecularmanipulator$getCalculationProgress();
            if (progress.phase() == CraftingCalculationProgressPhase.COMPLETED) {
                return Component.translatable(
                        "gui.appliedenhancements.calculation_result.title_timed",
                        pathLabel,
                        CraftingStorageFormatter.formatBytes(plan.getUsedBytes()),
                        appliedenhancements$formatDuration(progress.elapsedMillis()));
            }
        }
        return Component.translatable(
                "gui.appliedenhancements.calculation_result.title",
                pathLabel,
                CraftingStorageFormatter.formatBytes(plan.getUsedBytes()));
    }

    @Inject(method = "drawFG", at = @At("TAIL"))
    private void appliedenhancements$drawCalculationProgress(
            GuiGraphics graphics,
            int offsetX,
            int offsetY,
            int mouseX,
            int mouseY,
            CallbackInfo callback) {
        var screen = (CraftConfirmScreen) (Object) this;
        if (!ServerConfigSyncState.isProgressDisplayEnabled()
                || screen.getMenu().getPlan() != null
                || !(screen.getMenu() instanceof CraftingCalculationProgressMenuBridge bridge)) {
            return;
        }
        var progress = bridge.molecularmanipulator$getCalculationProgress();
        if (progress.phase() == CraftingCalculationProgressPhase.IDLE) {
            return;
        }

        int x = 8;
        int y = 16;
        int width = 202;
        graphics.fill(x, y, x + width, y + 2, 0xFF343845);

        int color = appliedenhancements$progressColor(progress);
        if (progress.phase() == CraftingCalculationProgressPhase.COMPLETED) {
            graphics.fill(x, y, x + width, y + 2, color);
        } else if (progress.phase() == CraftingCalculationProgressPhase.FAILED
                || progress.phase() == CraftingCalculationProgressPhase.CANCELLED) {
            graphics.fill(x, y, x + width, y + 2, color);
        } else if (progress.hasKnownTotal()) {
            long total = progress.totalUnits();
            int filled = total == 0
                    ? width
                    : (int) Math.min(width,
                            Math.max(1, Math.ceil(
                                    (double) progress.completedUnits() * width / total)));
            graphics.fill(x, y, x + filled, y + 2, color);
        } else {
            int segmentWidth = 40;
            int travel = width - segmentWidth;
            long cycle = Math.max(1, travel * 2L);
            long phase = (Util.getMillis() / 18L) % cycle;
            int segmentX = phase <= travel
                    ? (int) phase
                    : (int) (cycle - phase);
            graphics.fill(
                    x + segmentX,
                    y,
                    x + segmentX + segmentWidth,
                    y + 2,
                    color);
        }
    }

    private static Component appliedenhancements$progressTitle(
            CraftingCalculationProgressSnapshot progress) {
        Component engine = appliedenhancements$engineLabel(progress);
        Component phase = Component.translatable(
                "gui.appliedenhancements.calculation_progress.phase."
                        + appliedenhancements$phaseKey(progress.phase()));
        String elapsed = appliedenhancements$formatDuration(progress.elapsedMillis());

        if (progress.hasKnownTotal()) {
            return Component.translatable(
                    "gui.appliedenhancements.calculation_progress.title_known",
                    engine,
                    phase,
                    appliedenhancements$formatCompact(progress.completedUnits()),
                    appliedenhancements$formatCompact(progress.totalUnits()),
                    elapsed);
        }
        return Component.translatable(
                "gui.appliedenhancements.calculation_progress.title_unknown",
                engine,
                phase,
                appliedenhancements$formatCompact(Math.max(
                        progress.processedSteps(), progress.discoveredNodes())),
                elapsed);
    }

    private static Component appliedenhancements$engineLabel(
            CraftingCalculationProgressSnapshot progress) {
        if (progress.phase() == CraftingCalculationProgressPhase.QUEUED
                || progress.phase() == CraftingCalculationProgressPhase.WAITING_SLOT
                || progress.phase() == CraftingCalculationProgressPhase.PREPARING) {
            return Component.translatable(
                    "gui.appliedenhancements.calculation_progress.engine.selecting")
                    .withStyle(ChatFormatting.AQUA);
        }
        return switch (progress.path()) {
            case AELIS -> Component.translatable(
                    "gui.appliedenhancements.calculation_progress.engine.aelis")
                    .withStyle(ChatFormatting.GREEN);
            case AE2_FALLBACK -> Component.translatable(
                    "gui.appliedenhancements.calculation_progress.engine.ae2_fallback")
                    .withStyle(ChatFormatting.GOLD);
            case EXTERNAL -> Component.translatable(
                    "gui.appliedenhancements.calculation_progress.engine.external")
                    .withStyle(ChatFormatting.AQUA);
            case AE2_NATIVE -> Component.translatable(
                    "gui.appliedenhancements.calculation_progress.engine.ae2_native")
                    .withStyle(ChatFormatting.GRAY);
        };
    }

    private static String appliedenhancements$phaseKey(
            CraftingCalculationProgressPhase phase) {
        return switch (phase) {
            case IDLE -> "idle";
            case QUEUED -> "queued";
            case WAITING_SLOT -> "waiting_slot";
            case PREPARING -> "preparing";
            case AELIS_COMPILING -> "aelis_compiling";
            case AELIS_EXECUTING -> "aelis_executing";
            case AE2_CALCULATING -> "ae2_calculating";
            case BUILDING_PLAN -> "building_plan";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
        };
    }

    private static int appliedenhancements$progressColor(
            CraftingCalculationProgressSnapshot progress) {
        if (progress.phase() == CraftingCalculationProgressPhase.FAILED) {
            return 0xFFFF5555;
        }
        if (progress.phase() == CraftingCalculationProgressPhase.CANCELLED) {
            return 0xFF777777;
        }
        return switch (progress.path()) {
            case AELIS -> 0xFF55FF55;
            case AE2_FALLBACK -> 0xFFFFAA00;
            case EXTERNAL -> 0xFF55FFFF;
            case AE2_NATIVE -> 0xFF55FFFF;
        };
    }

    private static String appliedenhancements$formatCompact(long value) {
        if (value < 1_000) {
            return Long.toString(value);
        }
        String[] suffixes = { "K", "M", "B", "T", "Q" };
        double scaled = value;
        int suffix = -1;
        while (scaled >= 1_000 && suffix + 1 < suffixes.length) {
            scaled /= 1_000.0;
            suffix++;
        }
        String number = scaled >= 100 || scaled == Math.rint(scaled)
                ? "%.0f".formatted(scaled)
                : "%.1f".formatted(scaled);
        return number + suffixes[suffix];
    }

    private static String appliedenhancements$formatDuration(long elapsedMillis) {
        if (elapsedMillis < 1_000) {
            return elapsedMillis + "ms";
        }
        if (elapsedMillis < 10_000) {
            return "%.1fs".formatted(elapsedMillis / 1_000.0);
        }
        return elapsedMillis / 1_000 + "s";
    }
}
