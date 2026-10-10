package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MessageTemplatesTest {

    private static MessageTemplates.Context owing(String amount) {
        return new MessageTemplates.Context("Ahmed Al-Harbi", "A-203", "Tower A", 2, new BigDecimal(amount),
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 1, 31));
    }

    @Test
    void anAmountWithCentsKeepsBothCentDigits() {
        var english = MessageTemplates.rentReminder(false, owing("3799.50"));
        var arabic = MessageTemplates.rentReminder(true, owing("3799.5"));

        assertThat(english.body()).contains("totalling SAR 3799.50,");
        assertThat(arabic.body()).contains("بإجمالي 3799.50 ريال سعودي");
    }

    @Test
    void aWholeAmountHasNoDecimals() {
        var english = MessageTemplates.rentReminder(false, owing("9600.00"));
        var arabic = MessageTemplates.rentReminder(true, owing("9600.00"));

        assertThat(english.body()).contains("totalling SAR 9600,");
        assertThat(arabic.body()).contains("بإجمالي 9600 ريال سعودي");
    }
}
