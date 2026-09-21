package com.appliedenhancements.mixin;

import appeng.client.gui.NumberEntryType;
import appeng.client.gui.widgets.ConfirmableTextField;
import appeng.client.gui.widgets.NumberEntryWidget;
import com.appliedenhancements.ae2.LongNumberEntryWidgetBridge;
import com.appliedenhancements.ae2.ExactLongValueParser;
import java.text.DecimalFormat;
import java.util.OptionalLong;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Accessor mixin for NumberEntryWidget to allow setting text field max length.
 */
@Mixin(value = NumberEntryWidget.class, remap = false)
public abstract class NumberEntryWidgetAccessor implements LongNumberEntryWidgetBridge {
    @org.spongepowered.asm.mixin.Unique private boolean appliedenhancements$exactInput;
    @Shadow @Final private int normalTextColor;
    @Shadow @Final private int errorTextColor;
    @Shadow private appeng.client.gui.widgets.ValidationIcon validationIcon;

    @Override public void appliedenhancements$enableExactInput() { appliedenhancements$exactInput = true; textField.setMaxLength(257); }
    @Override public void appliedenhancements$setExactValue(java.math.BigInteger value) {
        textField.setValue(new java.math.BigDecimal(value).divide(java.math.BigDecimal.valueOf(type.amountPerUnit())).toPlainString());
    }
    @Override public java.util.Optional<java.math.BigInteger> appliedenhancements$getExactValue() {
        return com.appliedenhancements.ae2.ExactAmountParser.parse(textField.getValue(), decimalFormat, type.amountPerUnit())
                .filter(value -> value.compareTo(java.math.BigInteger.valueOf(minValue)) >= 0)
                .filter(value -> maxValue == Long.MAX_VALUE && com.appliedenhancements.network.ServerConfigSyncState.isBigIntegerEnabled()
                        || value.compareTo(java.math.BigInteger.valueOf(maxValue)) <= 0);
    }
    @org.spongepowered.asm.mixin.injection.Inject(method = "getLongValue", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void appliedenhancements$projectValidExact(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<OptionalLong> ci) {
        if (!appliedenhancements$exactInput) return;
        var value = appliedenhancements$getExactValue();
        ci.setReturnValue(value.isPresent() ? OptionalLong.of(value.get().min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValueExact()) : OptionalLong.empty());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "addQty", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void appliedenhancements$addExact(long delta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!appliedenhancements$exactInput) return;
        var value = appliedenhancements$getExactValue().orElse(java.math.BigInteger.ZERO)
                .add(java.math.BigInteger.valueOf(delta).multiply(java.math.BigInteger.valueOf(type.amountPerUnit())))
                .max(java.math.BigInteger.valueOf(minValue));
        if (maxValue != Long.MAX_VALUE || !com.appliedenhancements.network.ServerConfigSyncState.isBigIntegerEnabled())
            value = value.min(java.math.BigInteger.valueOf(maxValue));
        if (value.toString().length() <= 256) appliedenhancements$setExactValue(value);
        ci.cancel();
    }
    @org.spongepowered.asm.mixin.injection.Inject(method = "validate", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void appliedenhancements$validateExact(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!appliedenhancements$exactInput) return;
        boolean valid = appliedenhancements$getExactValue().isPresent();
        java.util.List<net.minecraft.network.chat.Component> tooltip = valid ? java.util.List.of()
                : java.util.List.of(net.minecraft.network.chat.Component.translatable("gui.appliedenhancements.invalid_exact_amount"));
        textField.setTextColor(valid ? normalTextColor : errorTextColor);
        textField.setTooltipMessage(tooltip);
        if (validationIcon != null) { validationIcon.setValid(valid); validationIcon.setTooltip(tooltip); }
        ci.cancel();
    }
    @Shadow
    @Final
    private ConfirmableTextField textField;

    @Shadow
    @Final
    private DecimalFormat decimalFormat;

    @Shadow
    private NumberEntryType type;

    @Shadow
    private long minValue;

    @Shadow
    private long maxValue;

    @Override
    public void appliedenhancements$setInputMaxLength(int maxLength) {
        textField.setMaxLength(maxLength);
    }

    @Override
    public OptionalLong appliedenhancements$getExactLongValue() {
        return ExactLongValueParser.parse(
                textField.getValue(),
                decimalFormat,
                type.amountPerUnit(),
                minValue,
                maxValue);
    }
}
