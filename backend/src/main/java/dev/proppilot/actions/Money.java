package dev.proppilot.actions;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class Money {

    private Money() {
    }

    /** {@code 3500} becomes {@code 3,500.00}: Latin digits in both languages, as everywhere in the app. */
    static String format(BigDecimal amount) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }
}
