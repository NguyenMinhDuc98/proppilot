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

    @Test
    void stringsReadsAnArrayOfStringsAndLeavesOutBlankOnes() throws Exception {
        assertThat(args("{\"codes\":[\" A-203 \",\"B-101\",\"\",\"  \"]}").strings("codes", 10))
                .containsExactly("A-203", "B-101");
    }

    @Test
    void stringsIsEmptyWhenMissingOrNullOrAnEmptyArray() throws Exception {
        assertThat(args("{}").strings("codes", 10)).isEmpty();
        assertThat(args("{\"codes\":null}").strings("codes", 10)).isEmpty();
        assertThat(args("{\"codes\":[]}").strings("codes", 10)).isEmpty();
        assertThat(new ToolArgs(null).strings("codes", 10)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"A-203\"", "7", "true", "{\"a\":\"A-203\"}"})
    void stringsRejectsWhatIsNotAnArray(String value) {
        assertThatThrownBy(() -> args("{\"codes\":" + value + "}").strings("codes", 10))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("codes must be an array of strings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1]", "[\"A-203\", 7]", "[null]", "[[\"A-203\"]]", "[{\"code\":\"A-203\"}]", "[true]"})
    void stringsRejectsAnEntryThatIsNotAString(String value) {
        assertThatThrownBy(() -> args("{\"codes\":" + value + "}").strings("codes", 10))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("codes must be an array of strings");
    }

    @Test
    void stringsRejectsMoreEntriesThanTheLimitAndAcceptsExactlyTheLimit() throws Exception {
        assertThat(args("{\"codes\":[\"a\",\"b\",\"c\"]}").strings("codes", 3)).hasSize(3);
        assertThatThrownBy(() -> args("{\"codes\":[\"a\",\"b\",\"c\",\"d\"]}").strings("codes", 3))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("codes must have at most 3 items");
    }

    @Test
    void aBoundedStringIsReturnedWhenItFits() throws Exception {
        assertThat(args("{\"q\":\"  late fee \"}").string("q", 10)).contains("late fee");
        assertThat(args("{\"q\":\"1234567890\"}").string("q", 10)).contains("1234567890");
        assertThat(args("{\"q\":\"line one\\nline\\ttwo\"}").string("q", 100)).contains("line one\nline\ttwo");
        assertThat(args("{}").string("q", 10)).isEmpty();
        assertThat(args("{\"q\":\"  \"}").string("q", 10)).isEmpty();
    }

    @Test
    void aBoundedStringRefusesMoreCharactersThanTheLimit() {
        assertThatThrownBy(() -> args("{\"q\":\"12345678901\"}").string("q", 10))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("q must be at most 10 characters");
        assertThatThrownBy(() -> args("{\"q\":\"" + "x".repeat(100_000) + "\"}").requireString("q", 500))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("q must be at most 500 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\u0000", "a\\u0000b", "\\u001b[31m", "\\u007f", "\\ud800", "x\\udc00", "\\ud800a",
            "\\udc00\\ud800"})
    void aBoundedStringRefusesControlCharactersAndHalvesOfSurrogatePairs(String escaped) {
        assertThatThrownBy(() -> args("{\"q\":\"" + escaped + "\"}").string("q", 50))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("q must be plain text");
    }

    @Test
    void aBoundedStringKeepsCompletePairsAndArabic() throws Exception {
        assertThat(args("{\"q\":\"fee \\ud83d\\ude00 غرامة\"}").string("q", 50)).contains("fee \uD83D\uDE00 غرامة");
    }

    @Test
    void requireStringWithALimitNamesTheMissingArgument() {
        assertThatThrownBy(() -> new ToolArgs(null).requireString("query", 500))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("query is required");
    }
}
