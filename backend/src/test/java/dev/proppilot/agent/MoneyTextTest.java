package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTextTest {

    @ParameterizedTest
    @CsvSource({
            "4800, 4800",
            "4800.00, 4800",
            "1E+3, 1000",
            "0, 0",
            "0.00, 0",
            "3799.5, 3799.50",
            "3799.50, 3799.50",
            "3799.05, 3799.05",
            "0.5, 0.50",
            "12345678.90, 12345678.90",
            "10.005, 10.01",
            "-250.5, -250.50"})
    void wholeAmountsHaveNoDecimalsAndCentsAlwaysHaveTwo(String amount, String text) {
        assertThat(MoneyText.of(new BigDecimal(amount))).isEqualTo(text);
    }
}
