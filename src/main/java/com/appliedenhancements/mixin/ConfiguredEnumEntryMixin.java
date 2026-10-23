package com.appliedenhancements.mixin;

import com.appliedenhancements.CraftingOrderMode;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Configured Forge 2.2.x creates literal labels from stored enum identifiers. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.client.screen.ChangeEnumScreen$Entry", remap = false)
public abstract class ConfiguredEnumEntryMixin {
    @Shadow @Final private Enum<?> enumValue;
    @Shadow @Final @Mutable private Component label;

    // Replace the completed label so no mapped Minecraft invocation is needed in the optional target.
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void appliedenhancements$translateEnum(CallbackInfo callback) {
        if (enumValue instanceof CraftingOrderMode mode) {
            label = mode.getTranslatedName();
        } else if (enumValue instanceof AelisCycleSeedPolicy policy) {
            label = policy.getTranslatedName();
        }
    }
}
