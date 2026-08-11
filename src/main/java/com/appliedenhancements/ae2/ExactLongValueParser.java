package com.appliedenhancements.ae2;

import appeng.client.gui.MathExpressionParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Objects;
import java.util.OptionalLong;

/** Converts AE2 number-entry expressions without BigDecimal-to-long wraparound. */
public final class ExactLongValueParser {
    private ExactLongValueParser() {
    }

    public static OptionalLong parse(
            String expression,
            DecimalFormat decimalFormat,
            long amountPerUnit,
            long minValue,
            long maxValue) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(decimalFormat, "decimalFormat");
        if (amountPerUnit <= 0 || minValue > maxValue) {
            throw new IllegalArgumentException("Invalid number-entry bounds or unit scale");
        }

        String normalized = expression.startsWith("=")
                ? expression.substring(1)
                : expression;
        var parsed = MathExpressionParser.parse(normalized, decimalFormat);
        if (parsed.isEmpty()) {
            return OptionalLong.empty();
        }

        BigDecimal internalValue = parsed.get();
        if (amountPerUnit == 1 && internalValue.scale() > 0) {
            return OptionalLong.empty();
        }

        try {
            BigDecimal externalValue = internalValue
                    .multiply(BigDecimal.valueOf(amountPerUnit))
                    .setScale(0, RoundingMode.UP);
            long value = externalValue.longValueExact();
            return value >= minValue && value <= maxValue
                    ? OptionalLong.of(value)
                    : OptionalLong.empty();
        } catch (ArithmeticException ignored) {
            return OptionalLong.empty();
        }
    }
}
