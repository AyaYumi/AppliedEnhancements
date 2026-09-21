package com.appliedenhancements.ae2;

import java.math.*;
import java.text.DecimalFormat;
import java.util.Optional;
import appeng.client.gui.MathExpressionParser;
import com.appliedenhancements.api.AelisExactRequest;

public final class ExactAmountParser {
    private ExactAmountParser() {}
    public static Optional<BigInteger> parse(String text, DecimalFormat format, long unit) {
        if (text == null || text.length() > 257 || unit <= 0) return Optional.empty();
        String normalized = text.startsWith("=") ? text.substring(1) : text;
        try {
            // Never pass literal integers through DECIMAL128 or floating point.
            BigDecimal value = normalized.matches("[0-9]+") ? new BigDecimal(normalized)
                    : MathExpressionParser.parse(normalized, format).orElseThrow();
            if (unit == 1 && value.stripTrailingZeros().scale() > 0) return Optional.empty();
            BigInteger amount = value.multiply(BigDecimal.valueOf(unit)).setScale(0, RoundingMode.UP).toBigIntegerExact();
            new AelisExactRequest(amount);
            return Optional.of(amount);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
}
