package com.appliedenhancements.mixin;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The translated tooltips already include limits; omit Configured's raw English suffixes. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.impl.neoforge.NeoForgeValue", remap = false)
public abstract class ConfiguredValueTooltipMixin {
    @Shadow @Final public ModConfigSpec.ValueSpec valueSpec;

    @Inject(method = "getComment", at = @At("HEAD"), cancellable = true, require = 0)
    private void appliedenhancements$translateTooltip(CallbackInfoReturnable<Component> callback) {
        String key = valueSpec.getTranslationKey();
        if (key != null && key.startsWith("appliedenhancements.config.") && I18n.exists(key + ".tooltip")) {
            callback.setReturnValue(Component.translatable(key + ".tooltip"));
        }
    }
}
