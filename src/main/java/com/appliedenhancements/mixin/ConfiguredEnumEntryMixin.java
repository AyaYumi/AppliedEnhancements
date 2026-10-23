package com.appliedenhancements.mixin;

import com.appliedenhancements.CraftingOrderMode;
import com.appliedenhancements.api.AelisCycleSeedPolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Configured 2.6.3 formats enum names without consulting NeoForge's translation contract. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.client.screen.ChangeEnumScreen$Entry", remap = false)
public abstract class ConfiguredEnumEntryMixin {
    @Shadow @Final private Enum<?> enumValue;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"),
            require = 0)
    private MutableComponent appliedenhancements$translateEnum(String label, Operation<MutableComponent> original) {
        if ((enumValue instanceof CraftingOrderMode || enumValue instanceof AelisCycleSeedPolicy)
                && enumValue instanceof TranslatableEnum translated) {
            return translated.getTranslatedName().copy();
        }
        return original.call(label);
    }
}
