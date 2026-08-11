package com.appliedenhancements.ae2;

import java.util.OptionalLong;

/**
 * Runtime bridge for AE2's NumberEntryWidget to support long value input.
 *
 * This interface allows the text field to accept more characters for long values.
 */
public interface LongNumberEntryWidgetBridge {
    /**
     * Sets the maximum length of the input text field.
     *
     * @param maxLength Maximum number of characters (20 for Long.MAX_VALUE)
     */
    void appliedenhancements$setInputMaxLength(int maxLength);

    OptionalLong appliedenhancements$getExactLongValue();
}
