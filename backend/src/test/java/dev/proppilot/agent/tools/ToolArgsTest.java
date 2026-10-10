package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToolArgsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolArgs args(String json) throws Exception {
        return new ToolArgs(JSON.readTree(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "5.0", "5.00", "0.5E1", "50E-1"})
    void integerAcceptsIntegralNumbersIncludingWholeDecimals(String number) throws Exception {
        assertThat(args("{\"months\":" + number + "}").integer("months", 1, 12)).contains(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.7", "2.5", "2.0000001", "0.4", "-2.7", "1e-3"})
    void integerRejectsEveryNonIntegralNumber(String number) {
        assertThatThrownBy(() -> args("{\"months\":" + number + "}").integer("months", -12, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be an integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"5\"", "\"two\"", "true", "[3]", "{\"n\":3}"})
    void integerRejectsEverythingThatIsNotANumber(String value) {
        assertThatThrownBy(() -> args("{\"months\":" + value + "}").integer("months", 1, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be an integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3000000000", "-3000000000", "1e30", "99999999999999999999"})
    void integerRejectsNumbersTooBigForAnInt(String number) {
        assertThatThrownBy(() -> args("{\"months\":" + number + "}").integer("months", 1, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be an integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "13", "-1", "13.0"})
    void integerRejectsValuesOutsideTheRange(String number) {
        assertThatThrownBy(() -> args("{\"months\":" + number + "}").integer("months", 1, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be between 1 and 12");
    }

    @Test
    void integerIsEmptyWhenMissingOrNull() throws Exception {
        assertThat(args("{}").integer("months", 1, 12)).isEmpty();
        assertThat(args("{\"months\":null}").integer("months", 1, 12)).isEmpty();
        assertThat(new ToolArgs(null).integer("months", 1, 12)).isEmpty();
    }

    @Test
    void boolReadsTrueAndFalse() throws Exception {
        assertThat(args("{\"all\":true}").bool("all")).contains(true);
        assertThat(args("{\"all\":false}").bool("all")).contains(false);
    }

    @Test
    void boolIsEmptyWhenMissingOrNull() throws Exception {
        assertThat(args("{}").bool("all")).isEmpty();
        assertThat(args("{\"all\":null}").bool("all")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "\"false\"", "1", "0", "1.0", "[true]", "{}"})
    void boolRejectsValuesThatAreNotBooleans(String value) {
        assertThatThrownBy(() -> args("{\"all\":" + value + "}").bool("all"))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("all must be a boolean");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e999", "-1e999", "1E400"})
    void decimalRejectsNumbersTooBigForADouble(String number) {
        // JSON parsers read these as an infinite double, which has no decimal value
        assertThatThrownBy(() -> args("{\"amount\":" + number + "}").decimal("amount"))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("amount must be a finite number");
        assertThatThrownBy(() -> args("{\"amount\":" + number + "}").signedDecimal("amount"))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("amount must be a finite number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"5\"", "true", "[5]", "{}"})
    void decimalRejectsEverythingThatIsNotANumber(String value) {
        assertThatThrownBy(() -> args("{\"amount\":" + value + "}").decimal("amount"))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("amount must be a number");
    }

    @Test
    void decimalRefusesNegativeNumbersButSignedDecimalKeepsThem() throws Exception {
        assertThatThrownBy(() -> args("{\"amount\":-0.5}").decimal("amount"))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("amount must not be negative");
        assertThat(args("{\"amount\":-0.5}").signedDecimal("amount")).contains(new java.math.BigDecimal("-0.5"));
        assertThat(args("{\"amount\":1250.75}").decimal("amount")).contains(new java.math.BigDecimal("1250.75"));
    }

    @Test
    void decimalIsEmptyWhenMissingOrNull() throws Exception {
        assertThat(args("{}").decimal("amount")).isEmpty();
        assertThat(args("{\"amount\":null}").signedDecimal("amount")).isEmpty();
    }

    @Test
    void dateReadsAnIsoDate() throws Exception {
        assertThat(args("{\"payment_date\":\"2026-10-01\"}").date("payment_date")).contains(java.time.LocalDate.of(2026, 10, 1));
        assertThat(args("{}").date("payment_date")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01/10/2026\"", "\"2026-13-01\"", "\"2026-02-30\"", "\"yesterday\"", "20261001", "true"})
    void dateRejectsAnythingThatIsNotAnIsoDate(String value) {
        assertThatThrownBy(() -> args("{\"payment_date\":" + value + "}").date("payment_date"))
                .isInstanceOf(ToolInputException.class)
                .hasMessageStartingWith("payment_date must be");
    }
}
