package com.appliedenhancements;

import org.slf4j.Logger;

import com.appliedenhancements.config.ConfigFileMigration;
import com.appliedenhancements.network.NetworkHandler;
import com.appliedenhancements.network.ServerConfigSyncEvents;
import com.mojang.logging.LogUtils;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

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

    public AppliedEnhancements() {
        IEventBus modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        NetworkHandler.register();
        ServerConfigSyncEvents.register(modEventBus);

        ConfigFileMigration.migrate(FMLPaths.CONFIGDIR.get());
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "appliedenhancements-common.toml");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(com.appliedenhancements.runtime.AelisSmartCycleBatchProvider::registerOmniAdapters);
    }

    public static net.minecraft.resources.ResourceLocation id(String path) {
        return new net.minecraft.resources.ResourceLocation(MODID, path);
    }

}
