package com.appliedenhancements.mixin;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.config.ConfiguredConfigPatch;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.neoforged.fml.config.ModConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Configured 2.6.3 shallowly replaces sections when saving changed values. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.impl.neoforge.NeoForgeConfig", remap = false)
public abstract class ConfiguredNeoForgeConfigMixin {
    @Shadow
    @Final
    protected ModConfig config;

    @WrapOperation(method = "update", at = @At(value = "INVOKE",
            target = "Lcom/electronwill/nightconfig/core/CommentedConfig;putAll(Lcom/electronwill/nightconfig/core/UnmodifiableConfig;)V"),
            require = 0)
    private void appliedenhancements$preserveUnchangedConfigValues(
            CommentedConfig target, UnmodifiableConfig changes, Operation<Void> original) {
        if (AppliedEnhancements.MODID.equals(config.getModId())) {
            ConfiguredConfigPatch.apply(target, changes);
        } else {
            original.call(target, changes);
        }
    }
}
