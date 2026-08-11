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
