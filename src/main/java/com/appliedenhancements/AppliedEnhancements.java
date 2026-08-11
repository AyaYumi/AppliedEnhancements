package com.appliedenhancements;

import org.slf4j.Logger;

import com.github.appliedenhancements.config.AppliedEnhancementsConfig;
import com.github.appliedenhancements.network.CraftingCalculationProgressPayload;
import com.github.appliedenhancements.network.CraftingCalculationPathPayload;
import com.appliedenhancements.network.NetworkHandler;
import com.appliedenhancements.network.ServerConfigSyncEvents;
import com.appliedenhancements.network.ServerConfigSyncPayload;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Applied Enhancements - Universal AE2 system enhancements
 *
 * Provides performance improvements and compatibility fixes for Applied Energistics 2:
 * - Pattern input validation caching
 * - Container item lookup caching
 * - Long-range crafting order support
 * - AE2WTLib wireless terminal compatibility
 * - Batch provider API for third-party integration
 */
@Mod(AppliedEnhancements.MODID)
public class AppliedEnhancements {
    public static final String MODID = "appliedenhancements";
    public static final String MOD_ID = MODID;
    public static final Logger LOGGER = LogUtils.getLogger();

    public AppliedEnhancements(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
        ServerConfigSyncEvents.register(modEventBus);

        // Register configurations
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "appliedenhancements-common.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, AppliedEnhancementsConfig.COMMON_SPEC, "appliedenhancements-maxfast.toml");

        LOGGER.info("Applied Enhancements initializing...");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info(
                "Applied Enhancements ready: patternCaching={}, longRangeCrafting={}, progressDisplay={}, maxFastMode={}",
                Config.ENABLE_PATTERN_CACHING.get(),
                AppliedEnhancementsConfig.COMMON.enableLongRangeCrafting.get(),
                AppliedEnhancementsConfig.COMMON.enableProgressDisplay.get(),
                AppliedEnhancementsConfig.COMMON.maxFastMode.get());
    }

    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        NetworkHandler.register(registrar);
        ServerConfigSyncPayload.register(registrar);
        CraftingCalculationProgressPayload.register(registrar);
        CraftingCalculationPathPayload.register(registrar);
    }
}
