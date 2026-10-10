package dev.proppilot.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money as it is written into a sentence: no digit grouping, and cents only when the amount has them. */
public final class MoneyText {

    private MoneyText() {
    }

    /** {@code 4800.00} is "4800" and {@code 3799.5} is "3799.50", so cents always show both digits. */
    public static String of(BigDecimal amount) {
        var trimmed = amount.stripTrailingZeros();
        return trimmed.scale() > 0
                ? trimmed.setScale(2, RoundingMode.HALF_UP).toPlainString()
                : trimmed.toPlainString();
    }
}
